package server.trainer;

import client.Character;
import client.inventory.Item;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import soloMapling.itemPool.ItemUtilities;
import tools.PacketCreator;

import java.awt.Point;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/** Persistent finite tables, with one serialized runtime owner per venue/channel. */
public final class TrainerVenueService {
    private static final Logger log = LoggerFactory.getLogger(TrainerVenueService.class);
    private static final Map<String, Table> tables = new ConcurrentHashMap<>();
    private static final AtomicBoolean started = new AtomicBoolean();
    private static java.util.concurrent.ScheduledFuture<?> ticks, refunds;
    private static volatile boolean recovered;
    private static final int POPULATION_PER_TABLE = 4;
    private TrainerVenueService() { }

    private static String key(MapleMap map) { return map.getWorld() + ":" + map.getChannelServer().getId() + ":" + map.getId(); }

    public static void recoverBeforeLogin() {
        try {
            TrainerVenueLedger.recoverBeforeLogin();
            recovered = true;
        } catch (SQLException failure) {
            throw new IllegalStateException("Venue recovery failed before login", failure);
        }
    }

    /** Called by the normal environment startup after bot templates and channels are ready. */
    public static synchronized void start() {
        if (!recovered || !started.compareAndSet(false, true)) return;
        // This server's existing economy and the venue ledger belong to world 0.
        var world = Server.getInstance().getWorld(0);
        if (world == null) throw new IllegalStateException("Venue world missing");
        for (var channel : world.getChannels()) for (int mapId : new int[]{105040401, 100000102}) {
            MapleMap map = channel.getMapFactory().getMap(mapId);
            Table table = new Table(map);
            tables.put(key(map), table);
            try { table.initialize(); }
            catch (Exception failure) {
                fault(map, "Table startup failed");
                table.cleanupActors();
                log.error("Venue startup {}", key(map), failure);
            }
        }
        ticks = TimerManager.getInstance().register(() -> {
            for (Table table : tables.values()) synchronized (table) {
                try { table.tick(System.currentTimeMillis()); }
                catch (Exception failure) { fault(table.map, "Round processing needs an audit"); log.error("Venue tick {}", key(table.map), failure); }
            }
        }, 100);
        refunds = TimerManager.getInstance().register(() -> {
            try {
                for (var refund : TrainerVenueLedger.pendingRefunds()) {
                    Character actor = world.getPlayerStorage().getCharacterById(refund.playerId());
                    if (actor != null) synchronized (actor) {
                        if (actor.isLoggedinWorld() && actor.getClient().getPlayer() == actor
                                && TrainerVenueLedger.refundBeforePlay(refund.roundId(), actor))
                            actor.dropMessage(5, "Your interrupted venue entry has been refunded.");
                    }
                }
            } catch (Exception failure) { log.error("Pending venue refunds retained for retry", failure); }
        }, 10_000);
    }

    public static synchronized void stop() {
        if (!started.getAndSet(false)) return;
        if (ticks != null) ticks.cancel(false);
        if (refunds != null) refunds.cancel(false);
        for (Table table : tables.values()) synchronized (table) {
            try { table.stop(); }
            catch (Exception failure) {
                // Keep unfinished journal rows recoverable; startup will reconcile or visibly fault.
                log.error("Venue shutdown left durable work for startup {}", key(table.map), failure);
            } finally { table.cleanupActors(); }
        }
        tables.clear();
        recovered = false;
    }

    /** Does not take the table monitor: pickup owns a drop lock when reporting a fault. */
    public static void fault(MapleMap map, String reason) {
        if (map == null) return;
        Table table = tables.get(key(map));
        if (table != null) table.fault = reason;
        try { TrainerVenueLedger.faultVenue(map.getId(), map.getChannelServer().getId(), reason); }
        catch (Exception failure) { log.error("Unable to persist venue fault {}", key(map), failure); }
    }

    public static void command(Character actor, String[] args) {
        Table table = !started.get() || actor == null || actor.getMap() == null ? null : tables.get(key(actor.getMap()));
        if (table == null) { if (actor != null) actor.dropMessage(5, "Visit the regular sauna or Henesys Department Store to watch or join a table."); return; }
        synchronized (table) {
            if (!started.get()) return;
            try {
                String action = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
                if (action.equals("medium") || action.equals("elite")) table.join(actor, action.equals("elite") ? 50_000_000 : 10_000_000);
                else if (action.equals("leave")) table.leave(actor);
                else actor.dropMessage(5, table.fault != null ? "Table paused: " + table.fault :
                        "Table " + table.phase + ". Watching is free. @venue medium: 10m; @venue elite: 50m. Two minutes; picked prizes are yours. @venue leave refunds only before play starts.");
            } catch (Exception failure) { fault(table.map, "Admission or settlement needs an audit"); log.error("Venue command {}", key(table.map), failure); actor.dropMessage(5, "The table paused; its transaction is retained for recovery."); }
        }
    }

    private static final class VenueBot extends BotSM {
        VenueBot(Character actor) { super(actor); botType = "VenueBot"; setRunning(true); }
        @Override public void updateState() { }
        @Override public boolean isAvailableForAmbientActions() { return false; }
    }

    static final class Table {
        final MapleMap map;
        final List<Character> bots = new ArrayList<>();
        final List<CompanionTaskService.EventLease> leases = new ArrayList<>();
        volatile String fault;
        String phase = "STARTING";
        UUID round;
        Character player;
        int tier, sequence;
        long next, ends, nextPitch;
        MapItem drop;
        TrainerVenueLedger.Asset pending;
        LocalDate restockDay;
        java.util.concurrent.Future<?> movement;
        int travelDirection = 1;
        Table(MapleMap map) { this.map = map; }
        int channel() { return map.getChannelServer().getId(); }
        Character host() { return bots.getFirst(); }

        void initialize() throws SQLException {
            if (TrainerVenueLedger.hasFault(map.getId(), channel())) { fault = "An interrupted round needs an audit"; return; }
            Point origin = map.getId() == 105040401 ? new Point(-185, 38) : new Point(45, 182);
            for (int i = 0; i < POPULATION_PER_TABLE; i++) {
                String name = "V" + (map.getId() == 105040401 ? "S" : "H") + channel() + "Actor" + i;
                if (map.getChannelServer().getPlayerStorage().getCharacterByName(name) != null)
                    throw new IllegalStateException("Venue name already online: " + name);
                Point point = map.getPointBelow(new Point(origin.x + (i - 1) * 45, origin.y - 10));
                if (point == null) throw new IllegalStateException("Venue platform missing");
                int id = BotGeneration.createBot(point, map, 1, 35, 55, 0, name);
                Character bot = map.getChannelServer().getPlayerStorage().getCharacterById(id);
                if (bot == null) throw new IllegalStateException("Venue actor missing");
                bots.add(bot);
                var role = i == 0 ? CompanionTaskService.EventRole.HOST : i == 3
                        ? CompanionTaskService.EventRole.SPECTATOR : CompanionTaskService.EventRole.PARTICIPANT;
                var reserved = CompanionTaskService.shared().reserveEvent("venue:" + key(map), id, map.getWorld(), channel(),
                        role, System.currentTimeMillis() + 30_000,
                        new CompanionTaskService.PriorActivity("VenueBot", map.getId(), map.getId())).orElseThrow();
                leases.add(reserved);
                leases.set(leases.size() - 1, CompanionTaskService.shared().commitEvent(reserved).orElseThrow());
                CharacterStorage.addActiveBot(id, new VenueBot(bot));
            }
            phase = "WAITING"; next = System.currentTimeMillis() + 15_000;
            restock();
        }

        void cleanupActors() {
            if (movement != null) movement.cancel(true);
            for (var lease : leases) CompanionTaskService.shared().releaseEvent(lease.botId(), lease.generation());
            leases.clear();
            for (Character bot : bots) {
                // Never remove a replacement actor that happens to have the same name.
                if (map.getChannelServer().getPlayerStorage().getCharacterById(bot.getId()) == bot) {
                    try { BotGeneration.removeBotFromServer(bot); }
                    catch (RuntimeException failure) { log.error("Venue actor cleanup id={}", bot.getId(), failure); }
                }
            }
            bots.clear();
        }

        void restock() throws SQLException {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (today.equals(restockDay)) return;
            // V2 issuance shares the same ledger daily budget with any historical V1 grants.
            // Existing AVAILABLE/CLAIMED receipts and items are never rewritten.
            var plan = TrainerVenueStockPlan.recipes(map.getId(), channel(), today);
            for (int i = 0; i < plan.size() && TrainerVenueLedger.availableCount(map.getId(), channel()) < 31; i++) {
                var stock = TrainerVenueStockPlan.materialize(plan.get(i));
                UUID asset = TrainerVenueStockPlan.assetId(map.getId(), channel(), today, i);
                TrainerVenueLedger.acquire(asset, TrainerVenueLedger.stepId(asset, "daily-budget"), map.getId(), channel(),
                        bots.get(i % 3).getName(), stock.item(), stock.budgetValue(), TrainerVenueStockPlan.REVISION);
            }
            restockDay = today;
        }

        void join(Character actor, int cost) throws SQLException {
            if (fault != null || !phase.equals("WAITING") || round != null) { actor.dropMessage(5, "Wait for the next admission window."); return; }
            if (actor.getTrade() != null || !actor.isAlive() || !actor.isLoggedinWorld()) return;
            if (TrainerSocialMemory.refuses(host(), actor)) { BotSpeak(host(), "I remember losing my stock to you. I'm sitting this one out."); return; }
            UUID id = UUID.randomUUID();
            TrainerVenueLedger.openRound(id, map.getId(), channel(), host().getName(), actor.getId(), cost);
            var asset = TrainerVenueLedger.reserve(id, TrainerVenueLedger.stepId(id, "first-reserve"),
                    cost == 50_000_000 ? 10_000_000 : 1, cost == 50_000_000 ? 100_000_000 : 9_999_999);
            if (asset.isEmpty()) { TrainerVenueLedger.closeRound(id); actor.dropMessage(5, "That tier is out of stock. No charge."); return; }
            round = id; player = actor; tier = cost; pending = asset.get(); sequence = 0;
            if (!TrainerVenueLedger.admit(round, actor)) {
                TrainerVenueLedger.returnEscrow(round, pending.id()); pending = null;
                TrainerVenueLedger.refundBeforePlay(round, actor); reset(10_000);
                actor.dropMessage(5, "Admission failed; no entry was taken."); return;
            }
            phase = "ESCROW"; next = System.currentTimeMillis() + 5_000;
            BotSpeak(host(), actor.getName() + ", your two-minute round starts in five seconds. @venue leave now for a refund; after the first drop, the entry is final.");
        }

        void leave(Character actor) throws SQLException {
            if (player != actor) { actor.dropMessage(5, "You are watching for free."); return; }
            if (phase.equals("ESCROW")) {
                if (pending != null) TrainerVenueLedger.returnEscrow(round, pending.id());
                pending = null;
                if (!actor.isLoggedinWorld()) TrainerVenueLedger.deferRefund(round);
                else {
                    if (!TrainerVenueLedger.refundBeforePlay(round, actor)) throw new SQLException("Entry refund pending");
                    actor.dropMessage(5, "Entry refunded.");
                }
                reset(10_000);
            } else { ends = System.currentTimeMillis(); actor.dropMessage(5, "Round ending; committed entry and claimed prizes are final."); }
        }

        void tick(long now) throws SQLException {
            if (fault != null || !started.get()) return;
            for (int i = 0; i < bots.size(); i++) {
                Character bot = bots.get(i);
                long generation = leases.get(i).generation();
                if (bot.getMap() != map || !bot.isAlive() || CompanionTaskService.shared().eventLease(bot.getId())
                        .filter(l -> l.generation() == generation).isEmpty()) {
                    fault(map, "Venue actor or lease lost"); return;
                }
            }
            if (now >= nextPitch) {
                BotSpeak(host(), "Watch free, or join the next table: @venue medium (10m) / @venue elite (50m). Finite prizes; two minutes.");
                nextPitch = now + 60_000;
            }
            if (drop != null) settleDrop(now);
            if (phase.equals("ESCROW") && (player.getMap() != map || !player.isLoggedinWorld())) {
                leave(player); return;
            }
            if (phase.equals("ROUND") && player != null && (!player.isLoggedinWorld() || player.getMap() != map)) ends = now;
            if (now < next) return;
            if (phase.equals("WAITING")) {
                restock();
                if (TrainerVenueLedger.availableCount(map.getId(), channel()) == 0) { next = now + 60_000; BotSpeak(host(), "Our stock is gone. Taking a break until the next budgeted restock."); return; }
                round = UUID.randomUUID(); tier = 0; player = null; sequence = 0;
                TrainerVenueLedger.openRound(round, map.getId(), channel(), host().getName(), null, 0);
                phase = "ROUND"; ends = now + 20_000;
            } else if (phase.equals("ESCROW")) { phase = "ROUND"; ends = now + 120_000; }
            if (phase.equals("ROUND")) {
                if (now >= ends) {
                    if (drop != null) { next = now + 100; return; }
                    if (pending != null) { TrainerVenueLedger.returnEscrow(round, pending.id()); pending = null; }
                    if (!TrainerVenueLedger.closeRound(round)) throw new SQLException("Round has unsettled assets");
                    BotSpeak(host(), "Round settled. Next admission window is open."); reset(15_000); return;
                }
                if (drop == null) expose(now);
            }
        }

        void expose(long now) throws SQLException {
            if (pending == null) pending = TrainerVenueLedger.reserve(round,
                    TrainerVenueLedger.stepId(round, "reserve:" + ++sequence), tier == 50_000_000 ? 10_000_000 : 1,
                    tier == 10_000_000 ? 9_999_999 : 100_000_000).orElse(null);
            if (pending == null) { next = now + 5_000; return; }
            Character owner = bots.stream().filter(b -> b.getName().equals(pending.ownerBotName())).findFirst().orElseThrow();
            if (!TrainerVenueLedger.expose(round, pending.id(), TrainerVenueLedger.stepId(round, "expose:" + sequence)))
                throw new SQLException("Asset exposure rejected");
            long deadline = now + (tier == 0 ? 2_000 : tier == 50_000_000 ? 1_150 : 1_400);
            drop = map.spawnItemDropNoExpire(owner, player == null ? owner : player, pending.item().copy(),
                    owner.getPosition(), player == null, true, round, pending.id(), deadline);
            if (drop == null) throw new SQLException("Venue drop could not spawn");
            pending = null;
            if (tier > 0) movePaidActors(owner);
            next = now + ThreadLocalRandom.current().nextLong(4_000, 6_001);
        }

        void movePaidActors(Character owner) {
            if (movement != null && !movement.isDone()) return;
            int center = map.getId() == 105040401 ? -185 : 45;
            if (host().getPosition().x >= center + 90) travelDirection = -1;
            else if (host().getPosition().x <= center - 90) travelDirection = 1;
            int direction = travelDirection;
            List<Character> moving = owner == host() ? List.of(owner) : List.of(host(), owner);
            movement = soloMapling.server.ExecutorServiceManager.getVirtualThreadExecutorService().submit(() -> {
                for (Character bot : moving) {
                    if (Thread.currentThread().isInterrupted() || !started.get() || fault != null
                            || bot.getMap() != map || CharacterStorage.getBotById(bot.getId()) == null) return;
                    Point at = bot.getPosition();
                    int x = Math.max(center - 120, Math.min(center + 120, at.x + direction * 40));
                    Point floor = map.getPointBelow(new Point(x, at.y - 10));
                    if (floor != null && Math.abs(floor.y - at.y) <= 10)
                        soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.BotMoveSmallDistanceX(bot, floor);
                }
            });
        }

        void settleDrop(long now) throws SQLException {
            MapItem current = drop;
            current.lockItem();
            try {
                if (drop.getCollectedByCharacterId() != 0) { drop = null; return; }
                boolean absent = map.getMapObject(drop.getObjectId()) != drop || drop.isPickedUp();
                if (!absent && tier == 0 && now - drop.getDropTime() >= 1_000 && !drop.pickupExpired(now)) {
                    Character winner = bots.get(1 + sequence % 2);
                    if (winner == drop.getDropper()) winner = bots.get(winner == bots.get(1) ? 2 : 1);
                    if (winner.getPosition().distanceSq(drop.getPosition()) <= 180L * 180L
                            && TrainerVenueLedger.claimToBot(round, drop.getVenueAssetId(), map.getId(), channel(), winner.getId(), winner.getName(),
                            TrainerVenueLedger.stepId(round, "bot-claim:" + sequence))) {
                        drop.markCollectedBy(winner);
                        map.pickItemDrop(PacketCreator.removeItemFromMap(drop.getObjectId(), 2, winner.getId(), false, -1), drop);
                        BotSpeak(winner, "Got it. That one's in my stock now.");
                        return;
                    }
                }
                if (absent || drop.pickupExpired(now)) {
                    if (!absent && !map.makeDisappearItemFromMap(drop))
                        throw new SQLException("Expired prize could not be removed");
                    if (!TrainerVenueLedger.returnUnclaimed(round, drop.getVenueAssetId(),
                            TrainerVenueLedger.stepId(round, drop.getVenueAssetId() + ":return:" + sequence)))
                        throw new SQLException("Unclaimed asset return rejected");
                    drop = null;
                }
            } finally {
                current.unlockItem();
            }
            if (drop != null && drop.isPickedUp()) drop = null;
        }

        void reset(long wait) {
            if (movement != null) movement.cancel(true);
            round = null; player = null; pending = null; drop = null; tier = 0; phase = "WAITING";
            next = System.currentTimeMillis() + wait;
        }

        void stop() throws SQLException {
            if (round == null || fault != null || phase.equals("SHUTDOWN")) return;
            if (drop != null) {
                MapItem current = drop;
                current.lockItem();
                try {
                    if (current.getCollectedByCharacterId() == 0) {
                        if (!current.isPickedUp() && !map.makeDisappearItemFromMap(current))
                            throw new SQLException("Shutdown prize could not be removed");
                        if (!TrainerVenueLedger.returnUnclaimed(round, current.getVenueAssetId(),
                                TrainerVenueLedger.stepId(round, current.getVenueAssetId() + ":shutdown-return")))
                            throw new SQLException("Shutdown prize needs reconciliation");
                    }
                } finally { current.unlockItem(); }
                drop = null;
            }
            if (pending != null) { TrainerVenueLedger.returnEscrow(round, pending.id()); pending = null; }
            if (player != null && phase.equals("ESCROW")) {
                TrainerVenueLedger.deferRefund(round);
                if (player.isLoggedinWorld() && !TrainerVenuePickup.hasUnresolved(player))
                    TrainerVenueLedger.refundBeforePlay(round, player);
            } else if (!TrainerVenueLedger.closeRound(round)) throw new SQLException("Shutdown round has unsettled stock");
            phase = "SHUTDOWN";
        }
    }
}

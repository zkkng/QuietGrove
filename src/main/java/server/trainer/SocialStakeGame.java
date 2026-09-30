package server.trainer;

import client.Character;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import soloMapling.itemPool.ItemUtilities;
import soloMapling.server.BotTiming;
import java.awt.Point;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

/** Nearby invitation -> observed bait -> personal escrow -> real drop -> durable settlement. */
public final class SocialStakeGame {
    private static final Logger log = LoggerFactory.getLogger(SocialStakeGame.class);
    private static final long OFFER_MS = 30_000, EXPOSE_MS = 20_000;
    private static final Map<Integer, Round> rounds = new ConcurrentHashMap<>();
    private static final Map<Integer, UUID> reservedBots = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> invitations = new ConcurrentHashMap<>();
    private static final class Round {
        final UUID id = UUID.randomUUID();
        final Character player;
        final MapleMap map;
        final long created = System.currentTimeMillis();
        final List<Character> candidates;
        volatile boolean closed;
        MapItem playerDrop;
        volatile MapItem botDrop;
        Character bot;
        UUID journal;
        TrainerVenueLedger.Asset asset;
        CompanionTaskService.EventLease lease;
        int retries;
        Round(Character player, List<Character> candidates) {
            this.player = player; this.map = player.getMap(); this.candidates = List.copyOf(candidates);
        }
    }
    private SocialStakeGame() { }
    public static boolean isReserved(int botId) { return reservedBots.containsKey(botId); }

    public static boolean onPublicChat(Character player, String message) {
        if (player == null || player.getMap() == null || player.getMap().getWorld() != 0
                || isBot(player) || !invitation(message)) return false;
        if (rounds.containsKey(player.getId())) return true;
        long now = System.currentTimeMillis();
        if (now - invitations.getOrDefault(player.getId(), 0L) < 60_000) return true;
        Point at = player.getPosition();
        if (at == null) return false;
        MapleMap map = player.getMap();
        List<Character> candidates = new ArrayList<>();
        for (Character bot : map.getMapPlayers().values()) {
            if (!isBot(bot) || !TrainerSocialStock.eligible(bot.getName(), map.getId(), map.getChannelServer().getId())
                    || bot.getPosition() == null || isReserved(bot.getId())
                    || CompanionTaskService.shared().owned(bot.getId())
                    || Math.abs(bot.getPosition().x - at.x) > 650 || Math.abs(bot.getPosition().y - at.y) > 350) continue;
            BotSM brain = CharacterStorage.getBotById(bot.getId());
            if (brain instanceof SocialBot && brain.getRunning() && brain.isAvailableForAmbientActions()) candidates.add(bot);
        }
        String spoken = message.toLowerCase(Locale.ROOT);
        candidates.sort(Comparator.comparingInt((Character b) -> spoken.contains(b.getName().toLowerCase(Locale.ROOT)) ? 0 : 1)
                .thenComparingDouble(b -> b.getPosition().distanceSq(at)));
        if (candidates.isEmpty()) return false;
        Round round = new Round(player, candidates.subList(0, Math.min(4, candidates.size())));
        if (rounds.putIfAbsent(player.getId(), round) != null) return true;
        invitations.put(player.getId(), now);
        if (invitations.size() > 4096) invitations.entrySet().removeIf(e -> now - e.getValue() > 60_000);
        BotTiming.after(350, () -> { synchronized (round) {
            if (!active(round)) { finish(round); return; }
            BotSpeak(round.candidates.getFirst(), "Drop something nearby and I'll see if I can match it. Anyone can grab an exposed stake.");
            inspect(round);
        }});
        return true;
    }

    static boolean invitation(String message) {
        if (message == null) return false;
        String words = message.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        if (words.matches(".*\\b(no|not|never|dont|don t|won t)\\b.*")) return false;
        return words.equals("drop game") || words.equals("drop game anyone") || words.contains("play drop game")
                || words.contains("play a drop game") || words.contains("start a drop game")
                || (words.contains("show yours") && words.contains("show mine"));
    }
    private static boolean active(Round r) {
        return !r.closed && rounds.get(r.player.getId()) == r && r.player.isLoggedinWorld()
                && r.player.isAlive() && !r.player.isHidden() && r.player.getMap() == r.map;
    }
    private static boolean baitVisible(Round r, Character bot) {
        return r.playerDrop != null && !r.playerDrop.isPickedUp()
                && r.map.getMapObject(r.playerDrop.getObjectId()) == r.playerDrop
                && bot.getMap() == r.map && bot.getPosition() != null
                && Math.abs(bot.getPosition().x - r.playerDrop.getPosition().x) <= 650
                && Math.abs(bot.getPosition().y - r.playerDrop.getPosition().y) <= 350;
    }
    private static void inspect(Round r) {
        if (!active(r) || System.currentTimeMillis() - r.created >= OFFER_MS) { finish(r); return; }
        for (var object : r.map.getItems()) {
            if (object instanceof MapItem drop && drop.getMeso() == 0 && !drop.isPickedUp() && drop.isPlayerDrop()
                    && drop.getOwnerId() == r.player.getId() && drop.getDropTime() >= r.created) {
                r.playerDrop = drop;
                offer(r, 0); return;
            }
        }
        BotTiming.after(250, () -> { synchronized (r) { inspect(r); }});
    }
    private static void offer(Round r, int index) {
        if (!active(r) || index >= r.candidates.size() || System.currentTimeMillis() - r.created >= OFFER_MS) { finish(r); return; }
        Character bot = r.candidates.get(index);
        BotSM brain = CharacterStorage.getBotById(bot.getId());
        if (!baitVisible(r, bot) || brain == null || !brain.getRunning() || !brain.isAvailableForAmbientActions()) {
            offer(r, index + 1); return;
        }
        if (TrainerSocialMemory.refuses(bot, r.player)) {
            BotSpeak(bot, "I remember losing my stake to you. I'm sitting this one out.");
            laterOffer(r, index + 1); return;
        }
        long target = estimatedValue(r.playerDrop.getItem());
        if (target <= 0) { BotSpeak(bot, "I don't know what that is worth. I'll pass."); finish(r); return; }
        if (reservedBots.putIfAbsent(bot.getId(), r.id) != null) { offer(r, index + 1); return; }
        var service = CompanionTaskService.shared();
        var prior = new CompanionTaskService.PriorActivity(brain.getBotType(), bot.getMapId(), bot.getMapId());
        var pending = service.reserveEvent("social-stake-" + r.id, bot.getId(), r.map.getWorld(),
                r.map.getChannelServer().getId(), CompanionTaskService.EventRole.PARTICIPANT,
                System.currentTimeMillis() + 90_000, prior);
        if (pending.isEmpty()) { reservedBots.remove(bot.getId(), r.id); offer(r, index + 1); return; }
        var committed = service.commitEvent(pending.get());
        if (committed.isEmpty()) {
            service.releaseEvent(bot.getId(), pending.get().generation());
            reservedBots.remove(bot.getId(), r.id); offer(r, index + 1); return;
        }
        r.bot = bot; r.lease = committed.get();
        try {
            TrainerSocialStock.ensure(bot);
            UUID journal = UUID.randomUUID();
            if (!TrainerVenueLedger.openSocialRound(journal, r.map.getId(), r.map.getChannelServer().getId(), bot.getName(), r.player.getId())) {
                releaseBot(r); laterOffer(r, index + 1); return;
            }
            r.journal = journal;
            r.asset = TrainerVenueLedger.reservePersonal(journal, TrainerVenueLedger.stepId(journal, "personal-reserve"),
                    Math.max(1, target / 2), Math.min(100_000_000L, target * 2), bot.getName()).orElse(null);
            if (r.asset == null) {
                TrainerVenueLedger.closeRound(journal); r.journal = null;
                BotSpeak(bot, "I can't match that with what I have left. I'm out.");
                releaseBot(r); laterOffer(r, index + 1); return;
            }
            BotTiming.after(750, () -> { synchronized (r) { expose(r); }});
        } catch (Exception failure) {
            log.error("Personal stake reservation retained for recovery invitation={}", r.id, failure);
            settle(r);
        }
    }
    private static void laterOffer(Round r, int index) {
        BotTiming.after(900, () -> { synchronized (r) { offer(r, index); }});
    }
    static long estimatedValue(Item item) {
        if (item == null) return 0;
        try { Integer value = ItemUtilities.getItemMarketValue(item);
            return value == null || value <= 0 ? 0 : value.longValue() * Math.max(1, item.getQuantity());
        } catch (RuntimeException unknown) { return 0; }
    }
    private static void expose(Round r) {
        if (!active(r) || !baitVisible(r, r.bot) || !r.id.equals(reservedBots.get(r.bot.getId()))
                || CompanionTaskService.shared().eventLease(r.bot.getId()).filter(l -> l.generation() == r.lease.generation()).isEmpty()) {
            settle(r); return;
        }
        try {
            if (!TrainerVenueLedger.expose(r.journal, r.asset.id(), TrainerVenueLedger.stepId(r.journal, "personal-expose"))) {
                settle(r); return;
            }
            r.botDrop = r.map.spawnItemDropNoExpire(r.bot, r.bot, r.asset.item(), new Point(r.bot.getPosition()),
                    true, true, r.journal, r.asset.id(), System.currentTimeMillis() + EXPOSE_MS);
            if (r.botDrop == null) { settle(r); return; }
            String name = ItemInformationProvider.getInstance().getName(r.asset.item().getItemId());
            BotSpeak(r.bot, "I'll show my " + (name == null ? "item" : name) + " for twenty seconds. That's from my own stake stock.");
            log.info("Personal stake exposed invitation={} round={} asset={} bot={}", r.id, r.journal, r.asset.id(), r.bot.getName());
            BotTiming.after(EXPOSE_MS, () -> { synchronized (r) { settle(r); }});
        } catch (Exception failure) {
            log.error("Personal stake exposure needs recovery round={}", r.journal, failure); settle(r);
        }
    }
    private static void settle(Round r) {
        if (r.closed) return;
        try {
            if (r.journal != null) {
                MapItem drop = r.botDrop;
                if (drop != null) drop.lockItem();
                try {
                    if (drop != null && drop.getCollectedByCharacterId() <= 0 && !drop.isPickedUp()
                            && !r.map.makeDisappearItemFromMap(drop)) throw new IllegalStateException("Personal stake drop removal failed");
                    // Only unclaimed stock can return; a committed human receipt is terminal.
                    TrainerVenueLedger.returnPersonalUnclaimed(r.journal);
                    if (!TrainerVenueLedger.closeRound(r.journal)) throw new IllegalStateException("Personal round still unsettled");
                } finally { if (drop != null) drop.unlockItem(); }
            }
            finish(r);
        } catch (Exception failure) {
            if (++r.retries == 1 || r.retries % 20 == 0) log.error("Personal stake settlement deferred round={}", r.journal, failure);
            BotTiming.after(r.retries < 6 ? 5_000 : 60_000, () -> { synchronized (r) { settle(r); }});
        }
    }
    /** Never take the round monitor while a caller still owns the ground-drop lock. */
    public static void onCommittedPickup(MapItem drop) {
        for (Round r : rounds.values()) if (r.botDrop == drop) {
            BotTiming.after(1, () -> { synchronized (r) { settle(r); }}); return;
        }
    }
    private static void releaseBot(Round r) {
        if (r.bot != null) reservedBots.remove(r.bot.getId(), r.id);
        if (r.lease != null) CompanionTaskService.shared().releaseEvent(r.lease.botId(), r.lease.generation());
        r.bot = null; r.lease = null;
    }
    private static void finish(Round r) {
        r.closed = true; rounds.remove(r.player.getId(), r); releaseBot(r);
    }
}

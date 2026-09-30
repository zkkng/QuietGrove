package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import net.server.Server;
import net.server.world.Party;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import java.util.*;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

/** Finite party hunts; map-local event dispatch, canonical companions and ordinary rewards. */
public final class BossRuntime {
    private static final BossRuntime INSTANCE = new BossRuntime();
    private static final class Hunt {
        final BossObjective objective;
        final BossIntentParser.Role requestedRole;
        final int channel;
        long absentAt, nextNotice, lastObserved;
        String loot = "for me";
        boolean finishing;
        boolean explicitGather;
        boolean boarded;
        final Set<Long> observedRoots = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Map<Integer,Long> instanceRoots = new java.util.concurrent.ConcurrentHashMap<>();
        final server.events.gm.EventTelemetry telemetry = new server.events.gm.EventTelemetry();
        final Set<server.events.gm.EventTelemetry> observedTraffic = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Hunt(BossObjective o, BossIntentParser.Role role, int channel) { objective = o; requestedRole = role; this.channel = channel; }
    }
    private final Map<CompanionTaskService.PartyKey,Hunt> hunts = new HashMap<>();
    private final Map<Integer,Hunt> bots = new HashMap<>();
    private final Map<MapleMap,Set<Hunt>> maps = new WeakHashMap<>();
    private final Map<Object,Long> instanceTokens = new WeakHashMap<>();
    private final Map<Integer,Long> clarifications = new HashMap<>();
    private record LootExit(CompanionTaskService.Task task, Character owner, MapleMap map, Set<Long> roots, long expires,
                            boolean continuing, java.awt.Point retreatGoal, CompanionCombat supplies) {}
    private final Map<Integer,LootExit> lootExits = new HashMap<>();
    private final Map<Integer,Long> nextPickup = new HashMap<>();
    private final Deque<List<String>> completedMetrics = new ArrayDeque<>();
    private long nextGeneration, nextInstance;
    private List<String> registryErrors;
    private BossRuntime() {}
    public static BossRuntime get() { return INSTANCE; }
    private synchronized long token(Object map) { return instanceTokens.computeIfAbsent(map, x -> ++nextInstance); }
    private BossObjective.Identity identity(Hunt h, Monster m) {
        var map = m.getMap();
        Object instance = h.objective.definition().restricted() ? map.getEventInstance() : map;
        if(instance==null) return null;
        // Existing multipart event scripts own phase spawns across their own exact instance.
        long encounter = h.objective.definition().restricted() ? token(instance) : m.getEncounterId();
        return new BossObjective.Identity(map.getWorld(), map.getChannelServer().getId(), token(instance), encounter);
    }
    private Character human(Hunt h) {
        var world = Server.getInstance().getWorld(h.objective.party().worldId());
        return world == null ? null : world.getPlayerStorage().getCharacterById(h.objective.ownerId());
    }
    public boolean chat(Character human, String message, Map<Integer,String> names) {
        var intent = BossIntentParser.parse(message, names);
        if (intent.kind() == BossIntentParser.Kind.NONE) return false;
        if (isBot(human) || human.getMap() == null) return false;
        if (intent.kind() == BossIntentParser.Kind.UNSUPPORTED) {
            human.dropMessage(5,"Recruitment for " + intent.bossKey() + " has no validated encounter adapter. Try a catalog boss such as Mano or Jr. Balrog.");
            return true;
        }
        if (intent.kind() == BossIntentParser.Kind.CLARIFY) {
            long now = System.currentTimeMillis();
            synchronized (this) {
                clarifications.entrySet().removeIf(e -> e.getValue() <= now);
                if (clarifications.containsKey(human.getId())) return true;
                clarifications.put(human.getId(), now + 15_000);
            }
            human.dropMessage(5, intent.bossKey().equals("balrog")
                    ? "Which Balrog? Say Jr. Balrog, boat Crimson Balrog, instanced Balrog, or easy Balrog."
                    : "Choose one boss for this hunt.");
            return true;
        }
        Party party = human.getParty();
        Hunt current;
        synchronized (this) { current = party == null ? null : hunts.get(new CompanionTaskService.PartyKey(human.getWorld(), party.getId())); }
        if (intent.kind() == BossIntentParser.Kind.STATUS) {
            human.dropMessage(5, current == null ? "No active boss hunt." : current.objective.definition().displayName()
                    + ": " + (current.objective.identity() == null ? "gathering/searching" : "encounter observed") + "; loot " + current.loot + ".");
            return true;
        }
        if (party != null && (party.getLeaderId() != human.getId() || isBot(human))) {
            human.dropMessage(5, "Ask your human party leader to change the hunt."); return true;
        }
        if (intent.kind() != BossIntentParser.Kind.RECRUIT) {
            if (current == null) return intent.kind() != BossIntentParser.Kind.CANCEL;
            if (!current.objective.command(human.getId(), null, human.getId())) return true;
            switch (intent.kind()) {
                case CANCEL -> {
                    if(intent.recipient()!=null) CompanionTaskService.shared().task(intent.recipient())
                            .filter(t->current.objective.actors().contains(new BossObjective.Actor(t.botId(),t.generation())))
                            .ifPresent(CompanionRuntime.get()::release);
                    else finish(current, message.trim().equalsIgnoreCase("retreat") ? BossObjective.Outcome.RETREATED
                            : BossObjective.Outcome.PLAYER_CANCELLED, "The leader ended the hunt.");
                }
                case GATHER -> {
                    if (!BossAccess.legalGather(human, current.objective.definition(), intent.gatherMap()))
                        human.dropMessage(5, "That gather point has no legal route for this hunt.");
                    else { current.objective.command(human.getId(), intent.gatherMap(), null); current.explicitGather = true;
                        human.dropMessage(5,"We'll gather at " + intent.gatherMap() + " through ordinary routes."); }
                }
                case LOOT -> {
                    if (current.objective.identity() != null) human.dropMessage(5, "Choose loot preference before engaging the boss.");
                    else { current.loot = intent.loot(); human.dropMessage(5, "Boss loot: " + current.loot + "."); }
                }
                default -> { }
            }
            return true;
        }
        synchronized (this) { if (registryErrors == null) registryErrors = BossRegistry.validate(); }
        if (!registryErrors.isEmpty()) {
            human.dropMessage(5, "Boss catalog could not be validated: " + registryErrors.getFirst()); return true;
        }
        var definition = BossRegistry.get(intent.bossKey());
        if (intent.mode() == BossObjective.Mode.HELP_CURRENT_ENCOUNTER
                && BossMonsterController.visibleIncident(human,definition)) {
            definition = new BossDefinition(definition.key(),definition.version(),definition.displayName(),definition.aliases(),
                    definition.roots(),definition.phases(),definition.finals(),List.of(human.getMapId()),BossDefinition.Type.FIELD,
                    definition.minimumLevel(),human.getMapId(),"",definition.source(),definition.absenceMs(),definition.deadlineMs());
        }
        String access = BossAccess.requestFailure(human, definition);
        if (!access.isEmpty()) { human.dropMessage(5, access); return true; }
        if (party == null && !Party.createParty(human, true)) return true;
        party = human.getParty();
        var key = new CompanionTaskService.PartyKey(human.getWorld(), party.getId());
        if (current != null && current.objective.definition().key().equals(definition.key())
                && current.objective.mode() == intent.mode()) {
            CompanionRuntime.get().recruitBoss(human, intent.recipient(), fingerprint(current)); return true;
        }
        int gather = intent.gatherMap() == null ? definition.maps().contains(human.getMapId())
                || definition.type() == BossDefinition.Type.BOAT && Set.of(101000300,200000111,101000301,200000112,200090001,200090011).contains(human.getMapId()) ? human.getMapId()
                : definition.gatherMap() : intent.gatherMap();
        if (!BossAccess.legalGather(human, definition, gather)) {
            human.dropMessage(5, "There is no legal route to that gather point. Meet at the boss's approach first."); return true;
        }
        if (current != null) finish(current, BossObjective.Outcome.PLAYER_CANCELLED, "The leader selected another boss.",true);
        Hunt hunt;
        synchronized (this) {
            hunt = new Hunt(new BossObjective(++nextGeneration, human.getId(), key, gather, definition, intent.mode(), System.currentTimeMillis()),
                    intent.role(), human.getMap().getChannelServer().getId());
            hunts.put(key, hunt);
        }
        server.events.gm.EventInstrumentation.register(fingerprint(hunt),List.of(),hunt.telemetry);
        for (var task : CompanionTaskService.shared().tasks()) if (task.party().equals(key) && task.ownerId() == human.getId()) {
            var actor = CharacterStorage.getBotById(task.botId());
            if (actor != null && BossSuitability.assess(actor.getChr(), definition, intent.role()).suitable()) attach(hunt, task);
            else CompanionRuntime.get().release(task);
        }
        index(hunt, human.getMap());
        if (intent.mode() == BossObjective.Mode.HELP_CURRENT_ENCOUNTER) {
            observe(hunt, human.getMap());
            if (hunt.objective.identity() == null) { finish(hunt, BossObjective.Outcome.TARGET_ABSENT, "No matching encounter is visible here."); return true; }
        }
        human.dropMessage(5, "Hunting " + definition.displayName() + ". Gather at " + gather
                + "; lead the route and use normal entry. Loot defaults to you; say 'loot shared' before fighting.");
        if (definition.key().equals("papulatus")) human.dropMessage(5,"Bring a Piece of Cracked Dimension (4031179) and use the normal Machine Room summon trigger; recruitment supplies no entry item.");
        CompanionRuntime.get().recruitBoss(human, intent.recipient(), fingerprint(hunt));
        return true;
    }
    private String fingerprint(Hunt h) { return "boss:" + h.objective.generation(); }
    public synchronized boolean requestValid(RecruitRequestCoordinator.Context c) {
        Hunt h = hunts.get(c.party());
        return h != null && h.objective.outcome() == null && fingerprint(h).equals(c.fingerprint());
    }
    public BossSuitability.Assessment candidate(RecruitRequestCoordinator.Context c, Character bot) {
        Hunt h; synchronized (this) { h = hunts.get(c.party()); }
        return h == null || !fingerprint(h).equals(c.fingerprint()) ? BossSuitability.Assessment.refuse("hunt expired")
                : BossSuitability.assess(bot, h.objective.definition(), h.requestedRole);
    }
    public double candidateRank(RecruitRequestCoordinator.Context context,Character bot,Character human) {
        Hunt h; synchronized(this) {h=hunts.get(context.party());}
        if(h==null) return Double.POSITIVE_INFINITY;
        var assessment=candidate(context,bot);
        return BossSuitability.rank(bot.getLevel(),h.objective.definition().minimumLevel(),assessment.hitChance(),
                bot.getPosition().distanceSq(human.getPosition()));
    }
    public void accepted(RecruitRequestCoordinator.Context c, CompanionTaskService.Task task) {
        Hunt h; synchronized (this) { h = hunts.get(c.party()); }
        if (h != null && fingerprint(h).equals(c.fingerprint())) attach(h, task);
    }
    private void attach(Hunt h, CompanionTaskService.Task task) {
        boolean added;
        synchronized (this) {
            added = h.objective.attach(task.botId(), task.generation());
            if (added) {
                bots.put(task.botId(), h);
            }
        }
        if (added) {
            var actor=CharacterStorage.getBotById(task.botId());
            if(actor!=null) BossCombatEvidence.clear(actor.getChr());
            server.events.gm.EventInstrumentation.bindBot(task.botId(),fingerprint(h));
            CompanionTaskService.shared().objective(task.botId(), task.generation(), CompanionTaskService.Objective.FIELD_BOSS);
        }
    }
    public synchronized boolean active(Character c) { return c != null && bots.containsKey(c.getId()); }
    public synchronized boolean combatPermitted(Character c) { return c != null && !lootExits.containsKey(c.getId()); }
    public boolean lootTick(Character bot, long generation) {
        var actor=CharacterStorage.getBotById(bot.getId());
        if(actor==null) return false;
        synchronized(actor) {
            if(actor.getChr()!=bot || CharacterStorage.getBotById(bot.getId())!=actor) return false;
            return lootTickOwned(bot,generation);
        }
    }
    private boolean lootTickOwned(Character bot,long generation) {
        LootExit exit;
        synchronized (this) { exit = lootExits.get(bot.getId()); }
        if (exit == null || exit.task().generation() != generation) return false;
        var current=CompanionTaskService.shared().task(bot.getId()).orElse(null);
        if(current==null || current.generation()!=generation) {
            synchronized(this) {lootExits.remove(bot.getId(),exit);nextPickup.remove(bot.getId());}
            return false;
        }
        if (!bot.isAlive()) return false; // The companion FSM performs canonical death recovery before release.
        long now = System.currentTimeMillis();
        boolean reached=exit.retreatGoal()!=null && Math.abs(bot.getPosition().x-exit.retreatGoal().x)<=35
                && Math.abs(bot.getPosition().y-exit.retreatGoal().y)<=100;
        if (reached || now >= exit.expires() || !exit.owner().isLoggedinWorld() || bot.getMap() != exit.map()
                || exit.owner().getMap() != exit.map() || exit.owner().getParty() != bot.getParty()) {
            synchronized (this) {
                if (!lootExits.remove(bot.getId(),exit)) return true;
                nextPickup.remove(bot.getId());
            }
            if(reached && bot.getMap()==exit.map() && bot.getEventInstance()==null) ordinaryRetreatExit(bot,exit.retreatGoal());
            completeLootExit(bot,exit); return true;
        }
        if(exit.retreatGoal()!=null) {
            exit.supplies().withdrawalSupplies(bot,generation);
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.enable(bot);
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.move(bot,exit.retreatGoal().x,exit.retreatGoal().y);
            return true;
        }
        moveForLoot(bot,exit,now); return true;
    }
    private void ordinaryRetreatExit(Character bot,java.awt.Point goal) {
        var map=bot.getMap();
        for(var portal:map.getPortals()) if(portal.getPortalStatus() && portal.getType()!=server.maps.Portal.DOOR_PORTAL
                && (portal.getScriptName()==null || portal.getScriptName().isEmpty()) && portal.getPosition().equals(goal)
                && portal.getTargetMapId()>=0 && portal.getTargetMapId()<900000000 && portal.getTargetMapId()!=map.getId()) {
            var destination=map.getChannelServer().getMapFactory().getMap(portal.getTargetMapId());
            var arrival=destination==null?null:destination.getPortal(portal.getTarget());
            if(arrival!=null) {soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(bot);bot.changeMap(destination,arrival);}
            return;
        }
    }
    private void completeLootExit(Character bot, LootExit exit) {
        var current = CompanionTaskService.shared().task(bot.getId()).orElse(null);
        if (current == null || current.generation() != exit.task().generation()) return;
        soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(bot);
        if (exit.continuing() && exit.owner().isLoggedinWorld() && exit.owner().getParty() == bot.getParty()
                && bot.getParty() != null && bot.getMap() != null
                && bot.getMap().getChannelServer().getId() == exit.task().channelId()) {
            if (bot.getEventInstance() != null) bot.getEventInstance().exitPlayer(bot);
            CompanionTaskService.shared().objective(bot.getId(),exit.task().generation(),exit.supplies()!=null
                    ? CompanionTaskService.Objective.RESTING : CompanionTaskService.Objective.TRAINING);
        } else CompanionRuntime.get().release(exit.task());
    }
    private void moveForLoot(Character bot, LootExit exit, long now) {
        synchronized (this) { if (now < nextPickup.getOrDefault(bot.getId(),0L)) return; nextPickup.put(bot.getId(),now+800); }
        var drop = bot.getMap().getItems().stream().filter(o -> o instanceof server.maps.MapItem).map(o -> (server.maps.MapItem)o)
                .filter(i -> i.getDropper() instanceof Monster m && exit.roots().contains(m.getEncounterId())
                        && !i.isPickedUp() && i.canBePickedBy(bot) && BossLoot.allowed(bot,i)
                        && i.getPosition().distanceSq(bot.getPosition()) <= 250*250)
                .min(Comparator.comparingDouble(i -> i.getPosition().distanceSq(bot.getPosition()))).orElse(null);
        if (drop == null) { soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(bot); return; }
        if (!BossLoot.pickup(bot,drop)) soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.move(bot,drop.getPosition().x,drop.getPosition().y);
    }
    public boolean controlEligible(Monster m) {
        if (m == null || m.getMap() == null) return false;
        if(m.isEncounterMarker()) return false;
        for (Hunt h : local(m.getMap())) if (h.objective.outcome() == null
                && h.objective.definition().maps().contains(m.getMap().getId())
                && observeTarget(h,m)) return true;
        return false;
    }
    public synchronized BossDefinition definition(Character c) {
        Hunt h = c == null ? null : bots.get(c.getId()); return h == null ? null : h.objective.definition();
    }
    public synchronized int travelGoal(Character bot, Character leader) {
        Hunt h = bots.get(bot.getId());
        if (h == null) return leader.getMapId();
        if (leader.getMapId() == h.objective.gatherMap()) h.explicitGather = false;
        if (h.explicitGather || h.objective.identity() == null && !h.objective.definition().maps().contains(leader.getMapId()))
            return h.objective.gatherMap();
        return leader.getMapId();
    }
    public synchronized boolean mapAllowed(Character c) {
        Hunt h = c == null || c.getParty() == null ? null : hunts.get(new CompanionTaskService.PartyKey(c.getWorld(), c.getPartyId()));
        return h != null && BossAccess.mapAllowed(c, h.objective.definition());
    }
    public boolean targetAllowed(Character bot, Monster m) {
        if(m.isEncounterMarker()) return false;
        Hunt h; synchronized (this) { h = bots.get(bot.getId()); }
        if(h!=null && h.objective.definition().phases().contains(m.getId()) && !h.objective.definition().attackTemplate(m.getId())) return false;
        return h == null || h.objective.outcome() == null && h.objective.definition().maps().contains(bot.getMapId())
                && m.getMap() == bot.getMap() && BossAccess.mapAllowed(bot, h.objective.definition())
                && observeTarget(h,m);
    }
    private boolean observeTarget(Hunt h, Monster m) {
        if (h.objective.definition().phases().contains(m.getId()) && !h.objective.definition().combatTemplate(m.getId())) return false;
        // Historical map indexes keep death callbacks valid, but cannot select a new target after the party leaves.
        if (h.objective.identity() == null && !present(h,m.getMap())) return false;
        if(!instanceRootAllowed(h,m,true)) return false;
        if (h.objective.observe(identity(h,m),m.getId(),m.getParentMobOid()>0?m.getEncounterRootTemplate():0)) {
            h.observedRoots.add(m.getEncounterId());
            BossLoot.preference(m.getEncounterId(),h.loot,h.objective.party().partyId()); return true;
        }
        // Only canonical summons inherit a root; another same-template world monster cannot join.
        return h.objective.outcome() == null && m.getParentMobOid() > 0 && h.observedRoots.contains(m.getEncounterId());
    }
    /** Separate canonical roots share an adapter instance, but duplicate roots cannot replace them. */
    private boolean instanceRootAllowed(Hunt h,Monster m,boolean claim) {
        var definition=h.objective.definition();
        if(!definition.restricted()) return true;
        var instance=m.getMap().getEventInstance();
        if(instance==null || !definition.eventScript().equals(instance.getEm().getName())) return false;
        int rootTemplate=m.getEncounterRootTemplate();
        if(!definition.roots().contains(rootTemplate)) return false;
        // The current canonical main Horntail helper owns its body/parts through intro 8810026.
        if(definition.key().equals("horntail") && rootTemplate==8810018) return false;
        Long root=claim ? h.instanceRoots.putIfAbsent(rootTemplate,m.getEncounterId()) : h.instanceRoots.get(rootTemplate);
        return claim && root==null || root!=null && root==m.getEncounterId();
    }
    private boolean present(Hunt h, MapleMap map) {
        Character owner = human(h);
        if (owner != null && owner.isLoggedinWorld() && owner.getMap() == map) return true;
        for (var a : h.objective.actors()) {
            var task = CompanionTaskService.shared().task(a.id()).orElse(null);
            var actor = CharacterStorage.getBotById(a.id());
            if (task != null && task.generation() == a.generation() && actor != null
                    && actor.getChr().isAlive() && actor.getChr().getMap() == map) return true;
        }
        return false;
    }
    private void index(Hunt h, MapleMap map) {
        if (map == null) return;
        synchronized (this) {
            if (h.finishing) return;
            h.observedTraffic.add(server.events.gm.EventInstrumentation.registerIfAbsent(fingerprint(h),map,h.telemetry));
            maps.computeIfAbsent(map, ignored -> new LinkedHashSet<>()).add(h);
        }
    }
    private void observe(Hunt h, MapleMap map) {
        if (h.objective.definition().maps().contains(map.getId())) for (Monster m : map.getAllMonsters()) {
            if (m.isAlive() && observeTarget(h,m)) h.lastObserved = System.currentTimeMillis();
        }
    }
    public void pump() {
        long started = System.nanoTime();
        try { pumpHunts(); }
        finally { BossTelemetry.sample(BossTelemetry.Stage.LIFECYCLE,System.nanoTime()-started); }
    }
    private void pumpHunts() {
        List<LootExit> exits; synchronized (this) { exits = List.copyOf(lootExits.values()); }
        for (var exit : exits) {
            var actor = CharacterStorage.getBotById(exit.task().botId());
            if (actor != null) lootTick(actor.getChr(),exit.task().generation());
            else synchronized (this) { lootExits.remove(exit.task().botId(),exit); nextPickup.remove(exit.task().botId()); }
        }
        List<Hunt> copy; synchronized (this) { copy = List.copyOf(hunts.values()); }
        long now = System.currentTimeMillis();
        for (Hunt h : copy) try {
            if (h.objective.outcome() != null) { finish(h, h.objective.outcome(), h.objective.reason()); continue; }
            Character owner = human(h);
            if (owner == null || !owner.isLoggedinWorld() || owner.getMap() == null || owner.getParty() == null
                    || owner.getPartyId() != h.objective.party().partyId()) {
                finish(h, BossObjective.Outcome.PLAYER_CANCELLED, "The owner left the session."); continue;
            }
            int leaderId = owner.getParty().getLeaderId();
            Character leader = owner.getWorldServer().getPlayerStorage().getCharacterById(leaderId);
            if (leader == null || isBot(leader) || !leader.isLoggedinWorld()) {
                finish(h, BossObjective.Outcome.PLAYER_CANCELLED, "The party has no active human leader."); continue;
            }
            h.objective.command(h.objective.ownerId(), null, leaderId);
            if (!leader.isAlive()) { finish(h, BossObjective.Outcome.RETREATED, "The human leader fell."); continue; }
            if (leader.getMap().getChannelServer().getId() != h.channel) { finish(h, BossObjective.Outcome.FAILED_ACCESS, "The party changed channels."); continue; }
            if (now >= h.objective.deadline()) { finish(h, BossObjective.Outcome.TIMEOUT, "The hunt deadline expired."); continue; }
            index(h, leader.getMap());
            observe(h, leader.getMap());
            boolean inArea = h.objective.definition().maps().contains(leader.getMapId());
            if (h.objective.definition().type() == BossDefinition.Type.BOAT) {
                if (inArea || Set.of(200090001,200090011).contains(leader.getMapId())) h.boarded = true;
                if (h.boarded && Set.of(101000300,200000100).contains(leader.getMapId()) && h.objective.identity() == null) {
                    finish(h,BossObjective.Outcome.TARGET_ABSENT,"No matching encounter appeared during this voyage."); continue;
                }
            }
            if (inArea && h.objective.identity() == null) {
                if (h.absentAt == 0) { h.absentAt = now; leader.dropMessage(5, "No matching boss is visible. We can wait briefly or search the approved area."); }
                // No inaccessible scheduler time is used to predict a spawn.
                if (now - h.absentAt >= h.objective.definition().absenceMs()) finish(h, BossObjective.Outcome.TARGET_ABSENT, "The boss did not appear during the search window.");
            } else h.absentAt = 0;
            if (h.objective.identity() != null) {
                for (Monster m : leader.getMap().getAllMonsters()) if (m.getEncounterId() == h.objective.identity().encounter()
                        || h.objective.definition().restricted() && h.objective.definition().phases().contains(m.getId()))
                    BossLoot.preference(m.getEncounterId(),h.loot,h.objective.party().partyId());
            }
            for (BossObjective.Actor a : h.objective.actors()) {
                var actor = CharacterStorage.getBotById(a.id());
                var task = CompanionTaskService.shared().task(a.id()).orElse(null);
                if (actor != null && task != null && task.generation() == a.generation()) {
                    index(h, actor.getChr().getMap());
                    BossAccess.tick(actor.getChr(), leader, h.objective.definition());
                }
            }
            if (h.objective.actors().stream().noneMatch(a -> CompanionTaskService.shared().task(a.id()).filter(t -> t.generation() == a.generation()).isPresent())
                    && now > h.objective.deadline() - h.objective.definition().deadlineMs() + 16_000)
                finish(h, BossObjective.Outcome.RETREATED, "No suitable companions remain.");
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(BossRuntime.class).error("Boss hunt {} failed", h.objective.generation(), failure);
            finish(h, BossObjective.Outcome.FAILED_INFRASTRUCTURE, "The encounter service failed.");
        }
        BossMonsterController.pump();
    }
    public static void damaged(Monster m, Character attacker, int actual) {
        BossCombatEvidence.damage(attacker,actual);
        for (Hunt h : INSTANCE.local(m.getMap())) if (h.objective.definition().maps().contains(m.getMap().getId())) {
            if (!INSTANCE.observeTarget(h,m)) continue;
            if (attacker.getWorld() == h.objective.party().worldId() && attacker.getPartyId() == h.objective.party().partyId())
                h.objective.partyDamage(INSTANCE.identity(h,m),m.getId(),attacker.getId(),actual);
            else h.objective.damage(INSTANCE.identity(h,m), m.getId(), attacker.getId(), actual);
        }
    }
    public static void removed(Monster m, boolean legitimateDeath) {
        for (Hunt h : INSTANCE.local(m.getMap())) if (h.objective.definition().maps().contains(m.getMap().getId())) {
            if (!h.objective.definition().combatTemplate(m.getId())) continue;
            if(!INSTANCE.instanceRootAllowed(h,m,false)) continue;
            h.objective.removed(INSTANCE.identity(h,m), m.getId(), legitimateDeath);
        }
    }
    private synchronized List<Hunt> local(MapleMap map) { return List.copyOf(maps.getOrDefault(map, Set.of())); }
    public void withdrawal(Character bot, BossObjective.Outcome outcome, String reason) {
        Hunt h; synchronized (this) { h = bots.get(bot.getId()); }
        if (h != null) finish(h, outcome, reason);
    }
    public synchronized void detached(int botId, long generation) {
        LootExit exit = lootExits.get(botId);
        if (exit != null && exit.task().generation() == generation) { lootExits.remove(botId); nextPickup.remove(botId); }
        Hunt h = bots.get(botId);
        if (h != null && h.objective.actors().contains(new BossObjective.Actor(botId,generation))) {
            bots.remove(botId); server.events.gm.EventInstrumentation.releaseBot(botId,fingerprint(h));
        }
    }
    public void cancelChannel(int world, int channel) {
        List<Hunt> closing;
        synchronized (this) {
            closing = hunts.values().stream().filter(h -> h.objective.party().worldId() == world && h.channel == channel).toList();
            lootExits.values().removeIf(e -> e.task().party().worldId() == world && e.task().channelId() == channel);
            nextPickup.keySet().removeIf(id -> !lootExits.containsKey(id));
        }
        for (Hunt h : closing) finish(h,BossObjective.Outcome.PLAYER_CANCELLED,"The channel ended.");
    }
    private void finish(Hunt h, BossObjective.Outcome outcome, String reason) {
        finish(h,outcome,reason,false);
    }
    private void finish(Hunt h, BossObjective.Outcome outcome, String reason,boolean retarget) {
        synchronized (this) {
            if (h.finishing) return;
            h.finishing = true; h.objective.finish(outcome, reason);
            hunts.remove(h.objective.party(), h);
            maps.values().forEach(set -> set.remove(h)); maps.values().removeIf(Set::isEmpty);
            for (var a : h.objective.actors()) bots.remove(a.id(),h);
        }
        for(var actorLease:h.objective.actors()) {
            var actor=CharacterStorage.getBotById(actorLease.id());
            if(actor!=null) BossCombatEvidence.clear(actor.getChr());
        }
        Character owner = human(h);
        List<String> metrics = new ArrayList<>();
        metrics.add(fingerprint(h)+" "+h.objective.definition().displayName()+" "+h.objective.outcome());
        metrics.add("Traffic includes all observed writes in the hunt's exact maps; concurrent hunts/events can share a stream.");
        h.observedTraffic.forEach(telemetry -> metrics.addAll(telemetry.report()));
        synchronized (this) {completedMetrics.addLast(List.copyOf(metrics));while(completedMetrics.size()>8) completedMetrics.removeFirst();}
        server.events.gm.EventInstrumentation.retire(fingerprint(h));
        CompanionRuntime.get().cancelBossRequests(h.objective.ownerId(), h.objective.party());
        if (owner != null && owner.isLoggedinWorld()) {
            owner.dropMessage(5, h.objective.definition().displayName() + ": " + h.objective.outcome()
                    + ". " + h.objective.reason());
        }
        for (var a : h.objective.actors()) CompanionTaskService.shared().task(a.id())
                .filter(t -> t.generation() == a.generation()).ifPresent(t -> {
                    boolean continuing = (retarget || h.objective.mode() == BossObjective.Mode.CONTINUING_PARTY
                            && h.objective.outcome() != BossObjective.Outcome.PLAYER_CANCELLED)
                            && owner != null && owner.isLoggedinWorld() && owner.getPartyId() == t.party().partyId()
                            && owner.getMap() != null && owner.getMap().getChannelServer().getId() == t.channelId();
                    if (h.objective.outcome() == BossObjective.Outcome.KILLED_WITH_CONTRIBUTION && h.loot.equals("shared")
                            && owner != null && owner.isLoggedinWorld()) {
                        synchronized (this) { lootExits.put(a.id(),new LootExit(t,owner,owner.getMap(),Set.copyOf(h.observedRoots),System.currentTimeMillis()+5000,continuing,null,null)); }
                    } else if(h.objective.outcome()==BossObjective.Outcome.RETREATED && owner!=null && owner.isLoggedinWorld()) {
                        CompanionTaskService.shared().objective(a.id(),a.generation(),CompanionTaskService.Objective.RESTING);
                        CompanionTaskService.shared().transition(a.id(),a.generation(),t.leaderId(),CompanionTaskService.State.FOLLOW);
                        var actor=CharacterStorage.getBotById(a.id());
                        var bot=actor==null?null:actor.getChr();
                        var goal=bot==null||!bot.isAlive()||bot.getMap()==null?Optional.<java.awt.Point>empty():BossPositions.retreat(bot,owner);
                        if(goal.isPresent()) {
                            CompanionTaskService.shared().transition(a.id(),a.generation(),t.leaderId(),CompanionTaskService.State.FOLLOW);
                            synchronized(this) {lootExits.put(a.id(),new LootExit(t,owner,bot.getMap(),Set.of(),System.currentTimeMillis()+15000,
                                    continuing,goal.get(),new CompanionCombat()));}
                        } else if(continuing) {
                            if(bot!=null && bot.getEventInstance()!=null) bot.getEventInstance().exitPlayer(bot);
                        }
                        else CompanionRuntime.get().release(t);
                    } else if (continuing) {
                        var actor = CharacterStorage.getBotById(a.id());
                        if (!retarget && actor != null && actor.getChr().getEventInstance() != null) actor.getChr().getEventInstance().exitPlayer(actor.getChr());
                        CompanionTaskService.shared().objective(a.id(),a.generation(),CompanionTaskService.Objective.TRAINING);
                    } else CompanionRuntime.get().release(t);
                });
    }
    public Set<MapleMap> activeMaps() {
        List<Integer> actorIds; synchronized (this) { actorIds = List.copyOf(bots.keySet()); }
        Set<MapleMap> current = new HashSet<>();
        for (int id : actorIds) {
            var actor = CharacterStorage.getBotById(id);
            if (actor != null && actor.getChr().getMap() != null) current.add(actor.getChr().getMap());
        }
        return Set.copyOf(current);
    }
    public List<String> metrics() {
        List<Hunt> current; List<List<String>> recent;
        synchronized (this) {current=List.copyOf(hunts.values());recent=List.copyOf(completedMetrics);}
        List<String> result=new ArrayList<>();
        for (Hunt hunt : current) {
            result.add(fingerprint(hunt)+" "+hunt.objective.definition().displayName()+"; exact-map traffic includes other visible activity.");
            hunt.observedTraffic.forEach(telemetry -> result.addAll(telemetry.report()));
        }
        if (current.isEmpty() && !recent.isEmpty()) result.addAll(recent.getLast());
        return List.copyOf(result);
    }
}

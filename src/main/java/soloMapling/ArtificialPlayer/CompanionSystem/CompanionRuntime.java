package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyOperation;
import server.TimerManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import java.util.*;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

/** One server-wide request pump; all ownership is delegated to the shared party/event registry. */
public final class CompanionRuntime implements RecruitRequestCoordinator.Gateway {
    private static final class Holder { private static final CompanionRuntime INSTANCE = new CompanionRuntime(); }
    private final CompanionTaskService tasks = CompanionTaskService.shared();
    private final Map<MapleMap, Long> mapTokens = Collections.synchronizedMap(new WeakHashMap<>());
    private long nextMapToken;
    private final RecruitRequestCoordinator requests = new RecruitRequestCoordinator(tasks,
            System::currentTimeMillis, this);
    private CompanionRuntime() {
        tasks.configureGlobalCap(config.YamlConfig.config.server.COMPANION_GLOBAL_CAP);
        BossMonsterController.ensureDriver();
        TimerManager.getInstance().register(() -> {
            try { requests.pump(); } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(CompanionRuntime.class).error("Companion request pump failed", e);
            }
        }, 250);
    }
    public static CompanionRuntime get() { return Holder.INSTANCE; }
    public static boolean active(Character bot) {
        if (soloMapling.ArtificialPlayer.HybridPilot.HybridPilotService.isPilot(bot)
                && !soloMapling.ArtificialPlayer.HybridPilot.HybridPilotBot.observed(bot.getMap())) return false;
        return bot != null && isBot(bot) && (CompanionTaskService.shared().task(bot.getId()).isPresent() || BossMonsterController.incidentActor(bot)
                || server.events.gm.EventBotRuntime.physical(bot) || server.events.gm.GmEventService.getInstance().realPresence(bot)
                || server.events.gm.IncidentService.exposed(bot));
    }
    /** Revalidate the generation, actual roster and live human before any combat mutation. */
    public static boolean combatAllowed(Character bot, long generation) {
        if (!BossRuntime.get().combatPermitted(bot)) return false;
        var task = bot == null ? null : CompanionTaskService.shared().task(bot.getId()).orElse(null);
        if (task == null) return BossMonsterController.combatAllowed(bot,generation);
        if (task == null || task.generation() != generation || task.objective()==CompanionTaskService.Objective.RESTING
                || !bot.isAlive() || !companionMap(bot)
                || (task.state() != CompanionTaskService.State.ENGAGE && task.state() != CompanionTaskService.State.SUPPORT)
                || bot.getParty() == null || bot.getParty().getId() != task.party().partyId()
                || bot.getParty().getMemberById(bot.getId()) == null
                || bot.getParty().getMemberById(task.ownerId()) == null) return false;
        Character leader = bot.getWorldServer().getPlayerStorage().getCharacterById(task.leaderId());
        return leader != null && !isBot(leader) && leader.isAlive() && leader.isLoggedinWorld()
                && leader.getParty() == bot.getParty() && leader.getMap() == bot.getMap()
                && bot.getMap().getChannelServer().getId() == task.channelId()
                && Math.abs(bot.getPosition().x - leader.getPosition().x) <= Math.min(config.YamlConfig.config.server.COMPANION_LEASH_X,
                    BossRuntime.get().active(bot) ? BossPositions.leashX(bot) : Integer.MAX_VALUE)
                && Math.abs(bot.getPosition().y - leader.getPosition().y) <= Math.min(config.YamlConfig.config.server.COMPANION_LEASH_Y,
                    BossRuntime.get().active(bot) ? BossPositions.leashY(bot) : Integer.MAX_VALUE);
    }
    private long token(MapleMap map) {
        synchronized (mapTokens) { return mapTokens.computeIfAbsent(map, ignored -> ++nextMapToken); }
    }
    public static boolean ordinaryMap(Character character) {
        if (character == null || character.getMap() == null || character.getEventInstance() != null) return false;
        int id = character.getMapId();
        // Instance/event maps and transport cabins require explicit adapters, never fallback warps.
        return id < 900000000 && id / 10000 != 20009 && id / 10000 != 20008
                && character.getMap().getChannelServer().getMapFactory().getMap(id) == character.getMap();
    }
    public static boolean companionMap(Character c) { return ordinaryMap(c) || BossRuntime.get().mapAllowed(c); }
    private Character owner(RecruitRequestCoordinator.Context context) {
        var world = Server.getInstance().getWorld(context.party().worldId());
        return world == null ? null : world.getPlayerStorage().getCharacterById(context.ownerId());
    }
    private RecruitRequestCoordinator.Context context(Character human, String fingerprint) {
        return new RecruitRequestCoordinator.Context(human.getId(),
                new CompanionTaskService.PartyKey(human.getWorld(), human.getParty().getId()),
                human.getMap().getChannelServer().getId(), token(human.getMap()), fingerprint);
    }
    public boolean chat(Character human, String message) {
        if (!config.YamlConfig.config.server.COMPANIONS_ENABLED) return false;
        if (human == null || isBot(human) || human.getMap() == null) return false;
        Map<Integer, String> names = new HashMap<>();
        for (Character c : human.getMap().getCharacters()) {
            if (CharacterStorage.getBotById(c.getId()) != null) names.put(c.getId(), c.getName());
        }
        if (BossRuntime.get().chat(human, message, names)) return true;
        var intent = RecruitIntentParser.parse(message, names);
        if (intent.kind() == RecruitIntentParser.Kind.NONE) return false;
        if (intent.kind() == RecruitIntentParser.Kind.CANCEL) {
            if (human.getParty() != null) requests.cancel(human.getId(), context(human, "cancel").party());
            for (var task : tasks.tasks()) if (task.ownerId() == human.getId()
                    && (intent.namedBotId() == null || intent.namedBotId() == task.botId())) release(task);
            return true;
        }
        recruit(human, intent.namedBotId(), intent.fingerprint());
        return true; // Never also enqueue the legacy RNG recruit conversation.
    }
    /** Direct invitations use the same deterministic admission path, with no conversation prerequisite. */
    public boolean directInvite(Character human, Character bot) {
        if (!config.YamlConfig.config.server.COMPANIONS_ENABLED) return false;
        BotSM actor = CharacterStorage.getBotById(bot.getId());
        if (priorType(actor) == null) return false; // Existing specialist/PQ handlers retain their invitations.
        recruit(human, bot.getId(), "direct:" + bot.getId());
        return true;
    }
    private void recruit(Character human, Integer named, String fingerprint) {
        if (!companionMap(human) || !human.isAlive()) return;
        if (human.getParty() == null && !Party.createParty(human, true)) return;
        if (human.getParty().getLeaderId() != human.getId()) {
            human.dropMessage(5, "Ask your party leader to recruit companions."); return;
        }
        var context = context(human, fingerprint);
        List<RecruitRequestCoordinator.Candidate> candidates = human.getMap().getCharacters().stream()
                .map(c -> CharacterStorage.getBotById(c.getId())).filter(Objects::nonNull)
                .filter(bot -> named == null || bot.getChr().getId() == named)
                .filter(bot -> eligible(context, bot.getChr().getId()))
                .sorted(Comparator.comparingDouble((BotSM bot) -> fingerprint.startsWith("boss:")
                        ? BossRuntime.get().candidateRank(context,bot.getChr(),human)
                        : bot.getChr().getPosition().distanceSq(human.getPosition())))
                .map(bot -> new RecruitRequestCoordinator.Candidate(bot.getChr().getId(),
                        new CompanionTaskService.PriorActivity(priorType(bot).name(),
                                bot instanceof TrainingBot training ? training.companionHomeMapId() : bot.getChr().getMapId(),
                                bot.getChr().getMapId())))
                .toList();
        if (requests.request(context, candidates) == 0)
            human.dropMessage(5, "No free companion can join here right now (party, availability or capacity).");
    }
    public void recruitBoss(Character human, Integer named, String fingerprint) { recruit(human,named,fingerprint); }
    public void cancelBossRequests(int ownerId, CompanionTaskService.PartyKey party) { requests.cancel(ownerId,party); }
    private static BotTypeManager.BotType priorType(BotSM actor) {
        if (actor instanceof soloMapling.ArtificialPlayer.HybridPilot.HybridPilotBot) return BotTypeManager.BotType.HYBRID_PILOT;
        if (actor instanceof TrainingBot) return BotTypeManager.BotType.TRAINING_BOT;
        if (actor instanceof SocialBot) return BotTypeManager.BotType.SOCIAL_BOT;
        if (actor instanceof TownWandererBot) return BotTypeManager.BotType.TOWN_WANDERER_BOT;
        return null;
    }
    @Override public int memberCount(RecruitRequestCoordinator.Context context) {
        Character human = owner(context);
        return human == null || human.getParty() == null ? 6 : human.getParty().getMembers().size();
    }
    @Override public boolean validOwner(RecruitRequestCoordinator.Context context) {
        Character human = owner(context);
        return human != null && !isBot(human) && human.isLoggedinWorld() && human.isAlive() && companionMap(human)
                && (!context.fingerprint().startsWith("boss:") || BossRuntime.get().requestValid(context))
                && human.getParty() != null && human.getParty().getId() == context.party().partyId()
                && human.getParty().getLeaderId() == human.getId()
                && human.getMap().getChannelServer().getId() == context.channelId()
                && token(human.getMap()) == context.mapInstanceId();
    }
    @Override public boolean eligible(RecruitRequestCoordinator.Context context, int botId) {
        Character human = owner(context);
        BotSM actor = CharacterStorage.getBotById(botId);
        if (human == null || actor == null || priorType(actor) == null || !actor.getRunning()
                || actor.getState() == BotSM.BotState.TRADING || actor.getState() == BotSM.BotState.FINISHED) return false;
        Character bot = actor.getChr();
        Character conversation = actor.getInteractors().getRespondant();
        if (bot.getMap() == null || !companionMap(bot)) return false;
        if (context.fingerprint().startsWith("boss:") && !BossRuntime.get().candidate(context,bot).suitable()) return false;
        return CompanionEligibility.eligible(new CompanionEligibility.Candidate(botId,
                actor.getClass().getSimpleName(), true, CompanionBuild.supported(bot), bot.isAlive(),
                bot.getWorld(), bot.getMap().getChannelServer().getId(), token(bot.getMap()), !bot.isHidden(),
                bot.getTrade() != null || bot.getShop() != null || bot.getPlayerShop() != null || bot.getMiniGame() != null
                        || actor.getState() != BotSM.BotState.RUNNING && actor.getState() != BotSM.BotState.IDLE,
                bot.getEventInstance() != null && !BossRuntime.get().mapAllowed(bot), soloMapling.ArtificialPlayer.BotBlockList.getInstance().isBlocked(botId, human.getId()),
                conversation == null ? 0 : conversation.getId(), bot.getParty() != null,
                tasks.ownedByOther(botId, context.party(), human.getId())),
                new CompanionEligibility.Requester(human.getId(), context.party().worldId(), context.channelId(), context.mapInstanceId()))
                && bot.getPosition().distanceSq(human.getPosition()) <= 900 * 900;
    }
    @Override public boolean canonicalJoin(RecruitRequestCoordinator.Context context, int botId) {
        BotSM actor = CharacterStorage.getBotById(botId);
        return actor != null && Party.joinParty(actor.getChr(), context.party().partyId(), true);
    }
    @Override public void accepted(RecruitRequestCoordinator.Context context, CompanionTaskService.Task task) {
        if (context.fingerprint().startsWith("boss:")) BossRuntime.get().accepted(context,task);
        BotSM previous = CharacterStorage.getBotById(task.botId());
        if (previous == null) { tasks.release(task.botId(), task.generation()); return; }
        synchronized (previous) {
            var current = tasks.task(task.botId()).orElse(null);
            if (current == null || current.generation() != task.generation()
                    || CharacterStorage.getBotById(task.botId()) != previous) return;
            try {
            if (previous instanceof soloMapling.ArtificialPlayer.HybridPilot.HybridPilotBot pilot) {
                previous.getChr().setWorldRates();
                if (!pilot.beginDuty(soloMapling.ArtificialPlayer.HybridPilot.HybridPilotBot.Mode.PARTY,
                        task.generation(), new CompanionBot(previous.getChr()))) {
                    release(task);
                    return;
                }
            } else {
            previous.setRunning(false);
            previous.getChr().setWorldRates();
            previous.stopScheduledTask();
            GCMovement.disable(previous.getChr());
            CompanionBot companion = new CompanionBot(previous.getChr());
            CharacterStorage.addActiveBot(task.botId(), companion);
            companion.setRunning(true);
            companion.startScheduledTask(0);
            }
            } catch (RuntimeException failure) {
                release(task);
                org.slf4j.LoggerFactory.getLogger(CompanionRuntime.class).error("Companion activation rolled back for {}", task.botId(), failure);
                return;
            }
        }
        var boss = BossRuntime.get().definition(previous.getChr());
        BotFullChat(previous.getChr(), boss == null ? "I'm in! I'll follow and fight with the party."
                : BossSuitability.assess(previous.getChr(),boss,BossIntentParser.Role.ANY).reason());
    }
    public void release(CompanionTaskService.Task token) {
        BotSM actor = CharacterStorage.getBotById(token.botId());
        if (actor == null) { tasks.release(token.botId(), token.generation()); return; }
        synchronized (actor) {
        if (tasks.release(token.botId(), token.generation()).isEmpty()) return;
        BossRuntime.get().detached(token.botId(),token.generation());
        Character bot = actor.getChr();
        GCMovement.disable(bot);
        CompanionActivity.clear(bot);
        CompanionIncomingDamage.clear(bot);
        BossCombatEvidence.clear(bot);
        if (bot.getEventInstance() != null) bot.getEventInstance().exitPlayer(bot);
        leaveCanonical(bot, token);
        if (actor instanceof soloMapling.ArtificialPlayer.HybridPilot.HybridPilotBot pilot) {
            pilot.endDuty(token.generation());
            return;
        }
        if (!tasks.owned(bot.getId()) && BotTypeManager.convertBotType(bot, BotTypeManager.BotType.valueOf(token.prior().botType()))) {
            if (CharacterStorage.getBotById(bot.getId()) instanceof TrainingBot restored)
                restored.restoreCompanionHome(token.prior().homeMapId());
        }
        }
    }
    /** Despawn, manual stop and conversion release ownership without restarting a prior activity. */
    public void cancelChannel(int worldId, int channelId) {
        requests.cancelChannel(worldId, channelId);
        for (var task : tasks.tasks()) if (task.party().worldId() == worldId && task.channelId() == channelId) {
            BotSM actor = CharacterStorage.getBotById(task.botId());
            if (actor == null) { tasks.release(task.botId(), task.generation()); continue; }
            synchronized (actor) {
                var current = tasks.task(task.botId()).orElse(null);
                if (current == null || current.generation() != task.generation()) continue;
                actor.setRunning(false);
                actor.stopScheduledTask(); // detach, stop movement and remove throttle exemption; never restore on shutdown.
                GCMovement.disable(actor.getChr());
                detached(actor.getChr()); // Covers a committed join whose replacement FSM has not activated yet.
            }
        }
        BossRuntime.get().cancelChannel(worldId,channelId);
    }
    /** Despawn, manual stop and conversion release ownership without restarting a prior activity. */
    public void detached(Character bot) {
        var task = tasks.task(bot.getId()).orElse(null);
        if (task == null || tasks.release(bot.getId(), task.generation()).isEmpty()) return;
        BossRuntime.get().detached(bot.getId(),task.generation());
        CompanionActivity.clear(bot);
        CompanionIncomingDamage.clear(bot);
        BossCombatEvidence.clear(bot);
        leaveCanonical(bot, task);
    }
    private void leaveCanonical(Character bot, CompanionTaskService.Task token) {
        Party party = bot.getParty();
        if (party != null && party.getId() == token.party().partyId()) {
            if (bot.getMap() != null) bot.getMap().removePartyMember(bot, party.getId());
            if (bot.getWorldServer().getParty(party.getId()) == party && bot.getMPC() != null)
                bot.getWorldServer().updateParty(party.getId(), PartyOperation.LEAVE, bot.getMPC());
            bot.setParty(null); bot.setMPC(null);
        }
    }
}

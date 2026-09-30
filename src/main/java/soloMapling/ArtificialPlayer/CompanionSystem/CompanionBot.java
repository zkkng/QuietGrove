package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import net.server.world.Party;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.CompanionNavigation;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTickService;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat;

/** A separate FSM replaces (does not run alongside) training/shop/break/chatter activity. */
public final class CompanionBot extends BotSM {
    private final long generation;
    private final CompanionCombat combat = new CompanionCombat();
    private long absentSince, routeSince, lastWarning;
    private Object lastMap;
    private java.awt.Point progressPosition;
    private long progressAt;
    private int failedPlans;
    public CompanionBot(Character character) {
        super(character);
        botType = "CompanionBot";
        state = BotState.RUNNING;
        generation = CompanionTaskService.shared().task(character.getId()).orElseThrow().generation();
        CompanionBuild.prepare(character);
    }
    @Override public synchronized void startScheduledTask(long ignored) {
        super.startScheduledTask(0);
        BotTickService.setNoThrottle(getChr().getId(), true);
        BotTickService.reschedule(getChr().getId(), 300);
    }
    @Override public synchronized void stopScheduledTask() {
        CompanionRuntime.get().detached(getChr());
        GCMovement.disable(getChr());
        BotTickService.setNoThrottle(getChr().getId(), false);
        super.stopScheduledTask();
    }
    @Override public void updateState() {
        try { updateCompanion(); }
        catch (RuntimeException failure) {
            CompanionTaskService.shared().task(getChr().getId())
                    .filter(task -> task.generation() == generation).ifPresent(CompanionRuntime.get()::release);
            throw failure;
        }
    }
    private void updateCompanion() {
        if (!getRunning()) return;
        Character bot = getChr();
        var task = CompanionTaskService.shared().task(bot.getId()).orElse(null);
        if (task == null || task.generation() != generation) return;
        if (BossRuntime.get().lootTick(bot,generation)) return;
        Party party = bot.getParty();
        if (party == null || party.getId() != task.party().partyId()
                || bot.getWorldServer().getParty(party.getId()) != party
                || party.getMemberById(task.ownerId()) == null) {
            CompanionRuntime.get().release(task); return;
        }
        if (!bot.isAlive()) {
            transition(task, task.leaderId(), CompanionTaskService.State.DEAD);
            GCMovement.stop(bot);
            // Real return-map revival; never resurrect in the battle map or continue earning EXP dead.
            if (bot.getMap() != null) {
                var town = bot.getMap().getReturnMap();
                boolean standard = bot.getEventInstance() == null || bot.getEventInstance().revivePlayer(bot);
                if (standard && town != null) {
                    if (town != bot.getMap()) bot.changeMap(town, town.getPortal(0));
                    if (bot.getMap() == town) bot.updateHp(Math.max(1, bot.getCurrentMaxHp() * 3 / 10));
                }
            }
            BossRuntime.get().withdrawal(bot,BossObjective.Outcome.RETREATED,"A companion fell; the hunt ended.");
            CompanionRuntime.get().release(task);
            return;
        }
        int leaderId = party.getLeaderId();
        Character leader = bot.getWorldServer().getPlayerStorage().getCharacterById(leaderId);
        if ((leader != null && isBot(leader)) || soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.botLoggedIn(leaderId))
            leader = bot.getWorldServer().getPlayerStorage().getCharacterById(task.ownerId());
        long now = System.currentTimeMillis();
        if (leader == null || !leader.isLoggedinWorld() || leader.getMap() == null
                || leader.getMap().getChannelServer().getId() != task.channelId()) {
            GCMovement.stop(bot);
            transition(task, task.leaderId(), CompanionTaskService.State.LEADER_GRACE);
            if (absentSince == 0) absentSince = now;
            if (now - absentSince >= config.YamlConfig.config.server.COMPANION_LEADER_GRACE_MS) CompanionRuntime.get().release(task);
            return;
        }
        absentSince = 0;
        if (!leader.isAlive() || !CompanionRuntime.companionMap(bot) || !CompanionRuntime.companionMap(leader)) {
            transition(task, leader.getId(), CompanionTaskService.State.LEADER_GRACE);
            GCMovement.stop(bot); return;
        }
        GCMovement.enable(bot);
        if (bot.getMap() != lastMap) { CompanionActivity.clear(bot); lastMap = bot.getMap(); routeSince = 0; }
        int destination = BossRuntime.get().travelGoal(bot,leader);
        if (bot.getMap() != leader.getMap() || destination != leader.getMapId()) {
            transition(task, leader.getId(), CompanionTaskService.State.RECOVER_ROUTE);
            if (routeSince == 0) routeSince = now;
            var navigation = CompanionNavigation.step(bot, leader,destination);
            if (navigation == CompanionNavigation.Result.ARRIVED && bot.getMap() != leader.getMap()) {
                routeSince = 0; GCMovement.stop(bot); return;
            }
            if (navigation == CompanionNavigation.Result.NO_ROUTE) {
                GCMovement.stop(bot);
                if (now - lastWarning >= 60000) { lastWarning = now; BotFullChat(bot, "I can't safely follow through that route. I'll wait here."); }
            }
            if (now - routeSince >= config.YamlConfig.config.server.COMPANION_ROUTE_TIMEOUT_MS) {
                BossRuntime.get().withdrawal(bot,BossObjective.Outcome.FAILED_ACCESS,"A companion could not reach the encounter.");
                CompanionRuntime.get().release(task);
            }
            return;
        }
        routeSince = 0;
        transition(task, leader.getId(), CompanionTaskService.State.FOLLOW);
        if(task.objective()==CompanionTaskService.Objective.RESTING) combat.withdrawalSupplies(bot,generation);
        var position = bot.getPosition();
        var target = leader.getPosition();
        if (Math.abs(position.x - target.x) > 110 || Math.abs(position.y - target.y) > 80) {
            if (progressPosition == null || progressPosition.distanceSq(position) > 64) {
                progressPosition = new java.awt.Point(position); progressAt = now; failedPlans = 0;
            } else if (now - progressAt >= 15000) {
                GCMovement.stop(bot); progressAt = now;
                if (++failedPlans >= 2) {
                    BossRuntime.get().withdrawal(bot,BossObjective.Outcome.RETREATED,"A companion could not reach a legal fighting position.");
                    CompanionRuntime.get().release(task); return;
                }
            }
        } else { progressPosition = null; failedPlans = 0; }
        if (task.objective()!=CompanionTaskService.Objective.RESTING
                && Math.abs(position.x - target.x) <= config.YamlConfig.config.server.COMPANION_LEASH_X
                && Math.abs(position.y - target.y) <= config.YamlConfig.config.server.COMPANION_LEASH_Y) {
            transition(task, leader.getId(), CompanionTaskService.State.ENGAGE);
            if (combat.tick(bot, leader)) return;
        }
        transition(task, leader.getId(), CompanionTaskService.State.FOLLOW);
        if (Math.abs(position.x - target.x) > 110 || Math.abs(position.y - target.y) > 80)
            GCMovement.move(bot, target.x + (bot.getId() % 5 - 2) * 30, target.y);
        else GCMovement.stop(bot);
    }
    private void transition(CompanionTaskService.Task task, int leader, CompanionTaskService.State state) {
        CompanionTaskService.shared().transition(task.botId(), generation, leader, state);
    }
}

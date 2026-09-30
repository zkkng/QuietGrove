package server.events.gm;

import client.Character;
import soloMapling.ArtificialPlayer.*;
import soloMapling.ArtificialPlayer.BotTypes.*;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.*;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Event ownership uses the companion registry; replacement and restoration each occur once. */
public final class EventBotRuntime {
    private static final Map<Integer,Long> prestige=new ConcurrentHashMap<>();
    private EventBotRuntime() {}
    /** Physical travel/competition must keep collision and resource costs even without a viewer. */
    public static boolean physical(Character actor) {
        if(actor==null || actor.getClient()==null || !BotHelpers.isBot(actor)) return false;
        return CompanionTaskService.shared().eventLease(actor.getId()).filter(l->l.committed()
                && l.role()!=CompanionTaskService.EventRole.HOST && l.worldId()==actor.getWorld()
                && l.channelId()==actor.getClient().getChannel()).isPresent();
    }
    public static BotTypeManager.BotType prior(BotSM bot) {
        if(bot instanceof TrainingBot) return BotTypeManager.BotType.TRAINING_BOT;
        if(bot instanceof SocialBot) return BotTypeManager.BotType.SOCIAL_BOT;
        if(bot instanceof TownWandererBot) return BotTypeManager.BotType.TOWN_WANDERER_BOT;
        return null;
    }
    public static boolean eligible(BotSM bot,int world,int channel) {
        if(bot==null || prior(bot)==null || !bot.getRunning() || bot.activityPaused() || bot.getState()==BotSM.BotState.TRADING
                || bot.getState()==BotSM.BotState.FINISHED) return false;
        Character actor=bot.getChr();
        return actor!=null && actor.isAlive() && !actor.isGM() && actor.getMap()!=null && !actor.isChangingMaps()
                && actor.getWorld()==world && actor.getClient().getChannel()==channel && actor.getParty()==null
                && actor.getTrade()==null && actor.getEventInstance()==null && actor.getPlayerShop()==null
                && bot.getInteractors().getListRespondants().isEmpty() && bot.getInteractors().getListInquirer().isEmpty()
                && !CompanionTaskService.shared().owned(actor.getId());
    }
    public static Optional<CompanionTaskService.EventLease> reserve(BotSM previous,String eventId,
            CompanionTaskService.EventRole role,int world,int channel) {
        return reserve(previous,eventId,role,world,channel,Integer.MAX_VALUE);
    }
    public static Optional<CompanionTaskService.EventLease> reserve(BotSM previous,String eventId,
            CompanionTaskService.EventRole role,int world,int channel,int worldBotLimit) {
        synchronized(previous) {
            if(CharacterStorage.getBotById(previous.getChr().getId())!=previous || !eligible(previous,world,channel)) return Optional.empty();
            var actor=previous.getChr();
            return CompanionTaskService.shared().reserveEvent(eventId,actor.getId(),world,channel,role,System.currentTimeMillis()+15_000,
                    new CompanionTaskService.PriorActivity(prior(previous).name(),previous instanceof TrainingBot t?t.companionHomeMapId():actor.getMapId(),actor.getMapId()),worldBotLimit);
        }
    }
    public static boolean activate(BotSM previous,CompanionTaskService.EventLease lease) {
        return activate(previous,lease,false,false);
    }
    public static boolean activateIncident(BotSM previous,CompanionTaskService.EventLease lease) {
        return activate(previous,lease,true,false);
    }
    public static boolean activateWaveHost(BotSM previous,CompanionTaskService.EventLease lease) {
        if(lease.role()!=CompanionTaskService.EventRole.HOST
                || !WaveInvasionService.getInstance().ownsHost(lease.eventId(),previous.getChr())) return false;
        return activate(previous,lease,false,true);
    }
    private static boolean activate(BotSM previous,CompanionTaskService.EventLease lease,boolean incident,boolean waveHost) {
        synchronized(previous) {
            if(CharacterStorage.getBotById(lease.botId())!=previous || CompanionTaskService.shared().eventLease(lease.botId())
                    .filter(l->l.generation()==lease.generation() && l.committed()).isEmpty()) return false;
            if(lease.role()==CompanionTaskService.EventRole.HOST
                    && !GmHostPresentation.activate(previous.getChr(),lease)) return false;
            previous.setRunning(false); previous.stopScheduledTask(); GCMovement.disable(previous.getChr());
            try {
            BotSM replacement=waveHost?new WaveHostBot(previous.getChr(),lease):incident?new IncidentBot(previous.getChr(),lease):new EventBot(previous.getChr(),lease);
            CharacterStorage.addActiveBot(lease.botId(),replacement); replacement.setRunning(true); replacement.startScheduledTask(lease.botId()%250);
            EventInstrumentation.bindBot(lease.botId(),lease.eventId());
            return true;
            } catch(RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(EventBotRuntime.class).error("Event {} bot {} activation failed",lease.eventId(),lease.botId(),failure);
                return false; // Caller releases the exact lease and restores the original activity.
            }
        }
    }
    public static void award(int actorId) { prestige.merge(actorId,1L,Long::sum); }
    public static long prestige(int actorId) { return prestige.getOrDefault(actorId,0L); }
    public static void release(int actorId,long expectedGeneration,boolean restore) {release(actorId,expectedGeneration,restore,0);}
    public static void release(int actorId,long expectedGeneration,boolean restore,long recoveryMs) {
        BotSM current=CharacterStorage.getBotById(actorId);
        var lease=CompanionTaskService.shared().eventLease(actorId).orElse(null);
        if(lease==null || lease.generation()!=expectedGeneration) return;
        if(current==null) { CompanionTaskService.shared().releaseEvent(actorId,lease.generation()); return; }
        if(lease.role()==CompanionTaskService.EventRole.HOST) GmHostPresentation.restore(current.getChr(),lease.generation());
        if(CompanionTaskService.shared().releaseEvent(actorId,lease.generation()).isEmpty()) return;
        EventInstrumentation.releaseBot(actorId,lease.eventId());
        soloMapling.server.BotTickService.runEventLifecycle(()-> { synchronized(current) {
            if(CharacterStorage.getBotById(actorId)!=current || CompanionTaskService.shared().owned(actorId)) return;
            current.setRunning(false); current.stopScheduledTask(); GCMovement.disable(current.getChr());
            CompanionIncomingDamage.clear(current.getChr()); CompanionActivity.clear(current.getChr());
            if(lease.role()==CompanionTaskService.EventRole.HOST && current.getChr().getMap()!=null) {
                var destination=current.getChr().getWarpMap(lease.prior().destinationMapId());
                if(destination!=null && destination.getPortal(0)!=null) current.getChr().changeMap(destination,destination.getPortal(0));
            }
            if(!current.getChr().isAlive() && current.getChr().getMap()!=null) {
                var town=current.getChr().getMap().getReturnMap();
                if(town!=null && town.getPortal(0)!=null) {
                    current.getChr().changeMap(town,town.getPortal(0));
                    current.getChr().updateHp(Math.max(1,current.getChr().getCurrentMaxHp()*3/10));
                }
            }
            if(restore && !CompanionTaskService.shared().owned(actorId)
                    && BotTypeManager.convertBotType(current.getChr(),BotTypeManager.BotType.valueOf(lease.prior().botType()))
                    && CharacterStorage.getBotById(actorId) instanceof TrainingBot training) training.restoreCompanionHome(lease.prior().homeMapId());
            BotSM restored=CharacterStorage.getBotById(actorId);
            if(restored!=null && recoveryMs>0) restored.waitFor(recoveryMs);
        }});
    }
}

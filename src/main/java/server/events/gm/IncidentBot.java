package server.events.gm;
import client.Character;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.CompanionSystem.*;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTickService;

/** Explicit incident lease FSM; shares real companion combat/resources without inventing a human leader. */
public final class IncidentBot extends BotSM {
    private final CompanionTaskService.EventLease lease;
    private final CompanionCombat combat=new CompanionCombat();
    IncidentBot(Character actor,CompanionTaskService.EventLease lease) {super(actor);this.lease=lease;botType="IncidentBot";state=BotState.RUNNING;}
    @Override public synchronized void startScheduledTask(long delay) {super.startScheduledTask(delay);BotTickService.reschedule(getChr().getId(),300);}
    @Override public synchronized void stopScheduledTask() {GCMovement.disable(getChr());super.stopScheduledTask();}
    @Override public void updateState() {if(getRunning()) IncidentService.getInstance().botTick(getChr(),lease,combat);}
}

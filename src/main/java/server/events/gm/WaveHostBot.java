package server.events.gm;

import client.Character;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

/** Hosting is a narrow lease, never a GM account or an unrestricted command executor. */
final class WaveHostBot extends BotSM {
    private final CompanionTaskService.EventLease lease;
    WaveHostBot(Character actor, CompanionTaskService.EventLease lease) {
        super(actor); this.lease = lease; botType = "GMEventHost"; state = BotState.RUNNING;
    }
    @Override public void updateState() {
        if (getRunning()) WaveInvasionService.getInstance().hostTick(getChr(), lease);
    }
    @Override public synchronized void stopScheduledTask() { GCMovement.disable(getChr()); super.stopScheduledTask(); }
}

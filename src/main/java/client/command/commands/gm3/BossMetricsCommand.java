package client.command.commands.gm3;

import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.CompanionSystem.*;

/** Read-only development instrumentation; reset clears measurements, never gameplay state. */
public final class BossMetricsCommand extends Command {
    { setDescription("Read boss timings, actual observed map traffic and scheduler lag; reset server timing samples."); }
    @Override public void execute(Client client,String[] params) {
        if(client.getPlayer().gmLevel()<3) return;
        if(params.length>0 && params[0].equalsIgnoreCase("reset")) BossTelemetry.reset();
        client.getPlayer().dropMessage(5,BossTelemetry.snapshot().toString());
        BossRuntime.get().metrics().forEach(line -> client.getPlayer().dropMessage(5,line));
    }
}

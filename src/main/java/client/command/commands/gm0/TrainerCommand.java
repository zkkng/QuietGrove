package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.trainer.TrainerService;

/** Pairing changes no normal game permissions; service independently checks operator allowlists. */
public final class TrainerCommand extends Command {
    { setDescription("Pair the owned-server development trainer, or revoke all powers."); }
    @Override public void execute(Client client, String[] params) {
        var actor = client.getPlayer();
        if (params.length == 1 && params[0].equalsIgnoreCase("off")) {
            TrainerService.getInstance().revoke(actor); actor.dropMessage(5, "Trainer revoked; all powers OFF."); return;
        }
        try { actor.dropMessage(5, "Private trainer pairing code (60 seconds): " + TrainerService.getInstance().issuePair(actor)); }
        catch (Exception denied) { actor.dropMessage(5, "Trainer unavailable: " + denied.getMessage()); }
    }
}

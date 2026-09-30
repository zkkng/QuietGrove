package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.trainer.TrainerVenueService;

public final class VenueCommand extends Command {
    { setDescription("Watch, join or leave the sauna and Henesys drop tables."); }
    @Override public void execute(Client client, String[] params) {
        TrainerVenueService.command(client.getPlayer(), params);
    }
}

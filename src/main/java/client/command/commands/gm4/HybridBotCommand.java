package client.command.commands.gm4;

import client.Client;
import client.command.Command;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.HybridPilot.HybridPilotService;
import soloMapling.server.ExecutorServiceManager;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HybridBotCommand extends Command {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    { setDescription("Limited hybrid bot pilot: spawn [1-3], status, here, off."); }

    @Override public void execute(Client client, String[] params) {
        var owner = client.getPlayer();
        if (params.length == 0 || params[0].equalsIgnoreCase("help")) {
            owner.yellowMessage("!hybrid spawn [1-3] | status | here | off. Default: zero bots; max three server-wide.");
            owner.yellowMessage("Level-20 melee pilot: nearby combat, real contact HP/death, addressed chat, empty-map sleep.");
            owner.yellowMessage("Say 'Hybrid1 hello' or 'Hybrid1 status'. No walking, trades, shops, loot pickup or party/event duty yet.");
            return;
        }
        String command = params[0].toLowerCase(Locale.ROOT);
        int count;
        try {
            if (!(command.equals("spawn") || command.equals("status") || command.equals("here") || command.equals("off"))
                    || params.length > (command.equals("spawn") ? 2 : 1)) throw new IllegalArgumentException();
            count = params.length == 2 ? Integer.parseInt(params[1]) : 1;
            if (count < 1 || count > HybridPilotService.LIMIT) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { owner.yellowMessage("Use !hybrid spawn [1-3], status, here or off."); return; }
        // No unbounded queue of spawn requests, and no static player variable shared by GM clients.
        if (!BUSY.compareAndSet(false, true)) { owner.yellowMessage("A pilot command is finishing; retry shortly."); return; }
        try {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    var pilot = HybridPilotService.get();
                    switch (command) {
                        case "spawn" -> pilot.spawn(owner, count).forEach(owner::yellowMessage);
                        case "status" -> pilot.status().forEach(owner::yellowMessage);
                        case "here" -> owner.yellowMessage("Moved " + pilot.here(owner) + " pilot bots here in this channel.");
                        case "off" -> owner.yellowMessage("Removed " + pilot.off() + " pilot bots. Existing bots were not converted.");
                    }
                } catch (Exception e) {
                    owner.yellowMessage("Hybrid pilot: " + e.getMessage());
                    LoggerFactory.getLogger(HybridBotCommand.class).warn("Hybrid pilot command {} failed", command, e);
                } finally { BUSY.set(false); }
            });
        } catch (RuntimeException e) { BUSY.set(false); throw e; }
    }
}

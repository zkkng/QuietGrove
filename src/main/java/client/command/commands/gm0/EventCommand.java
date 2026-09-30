package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.events.gm.EventDefinition;
import server.events.gm.GmEventService;

/** Player discovery and narrowly authorized GM controls share one service. No command impersonation. */
public final class EventCommand extends Command {
    { setDescription("List/status GM events; GMs may create, start, close-entry or cancel."); }
    @Override public void execute(Client client,String[] params) {
        var actor=client.getPlayer();
        var service=GmEventService.getInstance();
        String action=params.length==0 ? "status" : params[0].toLowerCase(java.util.Locale.ROOT);
        if(action.equals("list")) {
            for(var definition:EventDefinition.catalog()) actor.dropMessage(5,definition.key()+": "
                    +(definition.available()?"GM development adapter; player requests and bot hosting await verification":definition.unavailableReason()));
            return;
        }
        if(action.equals("status") || action.equals("capacity")) {
            actor.dropMessage(5,service.status(client.getChannelServer()));
            actor.dropMessage(5,server.events.gm.WaveInvasionService.getInstance().status(client.getChannelServer()));
            if(action.equals("capacity") && actor.gmLevel()>=3)
                for(String line:server.events.gm.EventDiagnostics.report(client.getChannelServer())) actor.dropMessage(5,line);
            if(action.equals("capacity") && actor.gmLevel()>=3) for(String line:service.telemetry(client.getChannelServer())) actor.dropMessage(5,line);
            if(actor.gmLevel()>=3) for(String line:server.events.gm.IncidentService.getInstance().status(client.getChannelServer())) actor.dropMessage(5,line);
            return;
        }
        if(action.equals("request")) {
            actor.dropMessage(5,params.length>=2?service.request(actor,params[1]):"Usage: @event request <approved-key>"); return;
        }
        if(actor.gmLevel()<3) { actor.dropMessage(5,"This event action requires GM rank 3."); return; }
        String result;
        try {
            result=switch(action) {
                case "waves" -> server.events.gm.WaveInvasionService.getInstance().start(actor,params.length>=2?params[1]:"bosses");
                case "stop-waves" -> server.events.gm.WaveInvasionService.getInstance().stop(actor);
                case "create" -> params.length>=2 ? service.create(actor,params[1],params.length>=3?Integer.parseInt(params[2]):50)
                        : "Usage: !event create <key> [development-operator-limit]";
                case "start" -> service.start(actor);
                case "open" -> service.open(actor);
                case "bot-host" -> params.length>=2?service.botHost(actor,params[1],params.length>=3?Integer.parseInt(params[2]):50):"Usage: !event bot-host <key> [operator-limit]";
                case "bot-host-trial" -> params.length>=2?service.trialBotHost(actor,params[1],params.length>=3?Integer.parseInt(params[2]):50,
                        params.length>=4?Integer.parseInt(params[3]):0):"Usage: !event bot-host-trial <key> [operator-limit] [bot-participants] (UNMEASURED)";
                case "bots", "trial-bots" -> params.length>=2?(action.equals("trial-bots")?service.trialBots(actor,Integer.parseInt(params[1]),params.length>=3 && params[2].equalsIgnoreCase("spectators")):
                        service.attend(actor,Integer.parseInt(params[1]),params.length>=3 && params[2].equalsIgnoreCase("spectators"))):"Usage: !event "+action+" <count> [spectators]";
                case "reload-capacity" -> server.events.gm.EventCapacityProfiles.reload();
                case "invasion" -> params.length>=2?server.events.gm.IncidentService.getInstance().create(actor,params[1],params.length>=3?Integer.parseInt(params[2]):1,
                        params.length<4 || params[3].equalsIgnoreCase("announced")):"Usage: !event invasion <approved-encounter> [count] [announced|silent]";
                case "invasion-trial" -> params.length>=4 && Integer.parseInt(params[3])>0?
                        server.events.gm.IncidentService.getInstance().create(actor,params[1],Integer.parseInt(params[2]),params.length<5 || params[4].equalsIgnoreCase("announced"),Integer.parseInt(params[3])):
                        "Usage: !event invasion-trial <approved-encounter> <monster-count> <positive-active-limit> [announced|silent] (UNMEASURED)";
                case "cancel-incident" -> params.length>=2?server.events.gm.IncidentService.getInstance().cancel(actor,params[1]):"Usage: !event cancel-incident <incident-id>";
                case "close-entry" -> service.closeEntry(actor);
                case "cancel" -> service.cancel(actor);
                case "finish" -> service.finish(actor);
                case "transfer-host" -> params.length>=2 ? service.transferHost(actor,params[1]) : "Usage: !event transfer-host <GM-name>";
                default -> "Actions: list, status, capacity, request; GM: create, open, start, bots, trial-bots, bot-host, bot-host-trial, reload-capacity, close-entry, finish, transfer-host, cancel, invasion, invasion-trial, cancel-incident, waves, stop-waves.";
            };
        } catch(NumberFormatException invalid) { result="Admission limit must be a positive integer."; }
        actor.dropMessage(5,result);
    }
}

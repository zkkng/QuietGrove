package server.events.gm;

import net.server.channel.Channel;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import soloMapling.server.BotPerfStats;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** On-demand census, never a per-bot/per-50ms world scan. Counts are not a capacity finding. */
public final class EventDiagnostics {
    private static final Set<String> ROLES=Set.of("SocialBot","TrainingBot","TownWandererBot");
    private EventDiagnostics() {}
    public static List<String> report(Channel channel) {
        int registered=0,live=0,running=0,alive=0,potential=0,owned=0;
        Map<Integer,Integer> mapCounts=new TreeMap<>();
        Map<String,Integer> roleCounts=new TreeMap<>();
        Map<String,Integer> builds=new TreeMap<>();int potionStock=0;
        for(var bot:CharacterStorage.getAllBots().values()) {
            var actor=bot.getChr();
            if(actor==null || actor.getClient()==null || actor.getWorld()!=channel.getWorld()
                    || actor.getClient().getChannel()!=channel.getId()) continue;
            registered++;
            if(actor.getMap()==null || channel.getPlayerStorage().getCharacterById(actor.getId())!=actor) continue;
            live++; if(bot.getRunning()) running++; if(actor.isAlive()) alive++;
            mapCounts.merge(actor.getMapId(),1,Integer::sum); roleCounts.merge(bot.getBotType(),1,Integer::sum);
            builds.merge("L"+(actor.getLevel()/10*10)+" job="+actor.getJob().getId(),1,Integer::sum);
            if(actor.getInventory(client.inventory.InventoryType.USE).list().stream().anyMatch(i->i.getItemId()/10000==200 && i.getQuantity()>0)) potionStock++;
            boolean hasOwner=CompanionTaskService.shared().owned(actor.getId()); if(hasOwner) owned++;
            if(ROLES.contains(bot.getBotType()) && bot.getRunning() && actor.isAlive() && !hasOwner
                    && actor.getParty()==null && actor.getTrade()==null && actor.getEventInstance()==null
                    && actor.getPlayerShop()==null && bot.getInteractors().getListRespondants().isEmpty()
                    && bot.getInteractors().getListInquirer().isEmpty()) potential++;
        }
        List<String> lines=new ArrayList<>();
        lines.add("Actual channel bot census: registered="+registered+", live="+live+", running="+running
                +", alive="+alive+", exclusive-owned="+owned+", potential social/training/wanderer="+potential);
        lines.add("Live bot maps: "+mapCounts+"; roles: "+roleCounts);
        lines.add("Actual level/job bands: "+builds+"; actors with finite potion items="+potionStock);
        lines.addAll(BotPerfStats.report());
        lines.add("Event/network/client capacity UNMEASURED. No highest passing active/visible count or renderer FPS is claimed.");
        lines.add("GM trial-bots/invasion-trial use explicit UNMEASURED development limits. Census candidates are not admitted competitors; player requests and bot hosts require verified profiles.");
        return List.copyOf(lines);
    }
}

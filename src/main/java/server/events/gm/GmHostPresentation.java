package server.events.gm;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import tools.PacketCreator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Temporary staff costume and nameplate, separate from account authority, builds and saved items. */
public final class GmHostPresentation {
    private record Presentation(Character actor, CompanionTaskService.EventLease lease,
                                String displayName, List<Item> equipment) {}
    private static final Map<Integer,Presentation> hosts=new ConcurrentHashMap<>();
    private static final int[] WIZET_ITEMS={1002140,1042003,1062007,1322013};
    private static final short[] WIZET_SLOTS={-1,-5,-6,-11};
    private GmHostPresentation() {}

    public static boolean activate(Character actor,CompanionTaskService.EventLease lease) {
        if(actor==null || lease==null || !BotHelpers.isBot(actor) || actor.isGM()
                || lease.botId()!=actor.getId() || !valid(actor,lease)) return false;
        synchronized(actor) {
            if(!valid(actor,lease)) return false;
            Presentation previous=hosts.get(actor.getId());
            if(previous!=null && previous.actor()==actor && previous.lease().equals(lease)) return true;
            if(previous!=null && valid(previous.actor(),previous.lease())) return false;
            var equipment=new java.util.ArrayList<Item>(WIZET_ITEMS.length);
            var items=ItemInformationProvider.getInstance();
            for(int i=0;i<WIZET_ITEMS.length;i++) {
                if(items.getEquipStats(WIZET_ITEMS[i])==null) return false;
                Item item=items.getEquipById(WIZET_ITEMS[i]);
                if(!(item instanceof Equip)) return false;
                item.setPosition(WIZET_SLOTS[i]);equipment.add(item);
            }
            String name=actor.getName();
            if(name==null || name.isBlank()) return false;
            name="[GM]"+name.replaceFirst("^(?:\\[GM\\])+","");
            hosts.put(actor.getId(),new Presentation(actor,lease,name,List.copyOf(equipment)));
            refresh(actor);return true;
        }
    }

    /** Restore before releasing the host lease or changing its identity; stale callbacks are harmless. */
    public static boolean restore(Character actor,long generation) {
        if(actor==null) return false;
        synchronized(actor) {
            Presentation presentation=hosts.get(actor.getId());
            if(presentation==null || presentation.actor()!=actor || presentation.lease().generation()!=generation
                    || !hosts.remove(actor.getId(),presentation)) return false;
            refresh(actor);return true;
        }
    }

    public static String displayName(Character actor) {
        Presentation presentation=current(actor);
        return presentation==null ? actor.getName() : presentation.displayName();
    }

    /** Real WZ equipment objects for avatar serialization; null means the character's normal gear. */
    public static List<Item> equipment(Character actor) {
        Presentation presentation=current(actor);
        return presentation==null ? null : presentation.equipment();
    }

    private static Presentation current(Character actor) {
        if(actor==null) return null;
        Presentation presentation=hosts.get(actor.getId());
        return presentation!=null && presentation.actor()==actor && valid(actor,presentation.lease()) ? presentation : null;
    }
    private static boolean valid(Character actor,CompanionTaskService.EventLease lease) {
        return lease.committed() && lease.role()==CompanionTaskService.EventRole.HOST
                && actor.getWorld()==lease.worldId() && actor.getClient()!=null
                && actor.getClient().getChannel()==lease.channelId()
                && CompanionTaskService.shared().eventLease(actor.getId()).filter(lease::equals).isPresent();
    }
    private static void refresh(Character actor) {
        if(actor.getMap()==null) return;
        for(Character viewer:actor.getMap().getCharacters()) {
            if(viewer==actor || viewer.getClient()==null || !viewer.isLoggedinWorld() || BotHelpers.isBot(viewer)) continue;
            viewer.sendPacket(PacketCreator.removePlayerFromMap(actor.getId()));
            viewer.sendPacket(PacketCreator.spawnPlayerMapObject(viewer.getClient(),actor,false));
        }
    }
}

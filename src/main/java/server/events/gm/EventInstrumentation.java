package server.events.gm;

import client.Character;
import server.maps.MapleMap;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Exact map/lease bindings. Hot path reads avoid world scans and per-packet event-registry locks. */
public final class EventInstrumentation {
    private record Owned(String id,EventTelemetry telemetry) {}
    private static final Map<MapleMap,Owned> maps=new ConcurrentHashMap<>();
    private static final Map<Integer,Owned> bots=new ConcurrentHashMap<>();
    private static final Map<String,Owned> owners=new ConcurrentHashMap<>();
    private EventInstrumentation() {}
    public static void register(String id,Collection<MapleMap> fields,EventTelemetry telemetry) {
        Owned owned=new Owned(id,telemetry);owners.put(id,owned);fields.forEach(map->maps.put(map,owned));
    }
    public static void bindBot(int actorId,String id) {Owned owner=owners.get(id);if(owner!=null) bots.put(actorId,owner);}
    public static EventTelemetry forMap(MapleMap map) {Owned owner=map==null?null:maps.get(map);return owner==null?null:owner.telemetry();}
    public static EventTelemetry registerIfAbsent(String id,MapleMap map,EventTelemetry telemetry) {
        Owned owner=owners.computeIfAbsent(id,key->new Owned(id,telemetry));
        Owned existing=maps.putIfAbsent(map,owner);
        return existing==null?owner.telemetry():existing.telemetry();
    }
    public static void releaseBot(int actorId,String id) {bots.computeIfPresent(actorId,(key,value)->value.id().equals(id)?null:value);}
    public static void retire(String id) {
        owners.remove(id);maps.entrySet().removeIf(e->e.getValue().id().equals(id));bots.entrySet().removeIf(e->e.getValue().id().equals(id));
    }
    public static EventTelemetry forActor(Character actor) {
        if(actor==null || actor.getMap()==null) return null;
        Owned owner=bots.get(actor.getId());if(owner==null) owner=maps.get(actor.getMap());
        return owner==null?null:owner.telemetry();
    }
    public static EventTelemetry forBot(int id) {Owned owner=bots.get(id);return owner==null?null:owner.telemetry();}
    /** Only committed competitors preserve action cadence. Observation alone never exempts spectators/hosts. */
    public static boolean participantCadence(int id) {
        if(!bots.containsKey(id)) return false;
        var lease=soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService.shared().eventLease(id).orElse(null);
        return lease!=null && lease.committed() && lease.role()==soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService.EventRole.PARTICIPANT;
    }
}

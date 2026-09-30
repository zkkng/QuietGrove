package server.events.gm;
import server.maps.MapleMap;
import java.util.*;

/** Shared identity leases for classic sessions and public incidents. */
public final class EventMapLeases {
    private static final Map<MapleMap,String> owners=new IdentityHashMap<>();
    private EventMapLeases() {}
    public static synchronized boolean acquire(String id,Collection<MapleMap> maps) {
        if(id==null || id.isBlank() || maps.isEmpty() || maps.stream().anyMatch(m->m==null || owners.containsKey(m))) return false;
        maps.forEach(m->owners.put(m,id)); return true;
    }
    public static synchronized void release(String id) { owners.values().removeIf(id::equals); }
    public static synchronized boolean owned(MapleMap map,String id) { return id.equals(owners.get(map)); }
}

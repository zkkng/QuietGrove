package gmevents;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import server.events.gm.IncidentAwareness;
import server.events.gm.IncidentService;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;

/** One-shot read-only awareness/lease-role census for an active incident. */
public final class IncidentRoleProbe {
    public static void agentmain(String name, Instrumentation ignored) throws Exception {
        if (name == null || !name.matches("[a-zA-Z0-9._-]{1,80}")) throw new IllegalArgumentException("basename");
        Field activeField = IncidentService.class.getDeclaredField("incidents");
        activeField.setAccessible(true);
        Map<?, ?> active = (Map<?, ?>) activeField.get(IncidentService.getInstance());
        StringBuilder report = new StringBuilder("utc=").append(Instant.now()).append('\n');
        for (Object incident : new ArrayList<>(active.values())) {
            Field awarenessField = incident.getClass().getDeclaredField("awareness");
            Field leasesField = incident.getClass().getDeclaredField("leases");
            Field activeLimitField = incident.getClass().getDeclaredField("trialActiveLimit");
            Field mapField = incident.getClass().getDeclaredField("map");
            Field idField = incident.getClass().getDeclaredField("id");
            for (Field field : new Field[]{awarenessField,leasesField,activeLimitField,mapField,idField}) field.setAccessible(true);
            IncidentAwareness awareness = (IncidentAwareness) awarenessField.get(incident);
            Map<?, ?> leases = (Map<?, ?>) leasesField.get(incident);
            var map = (server.maps.MapleMap) mapField.get(incident);
            Map<String, Integer> states = new TreeMap<>(), roles = new TreeMap<>();
            for (IncidentAwareness.Response response : awareness.responses())
                states.merge(response.state().name(), 1, Integer::sum);
            for (Object value : leases.values()) {
                var lease = (CompanionTaskService.EventLease) value;
                roles.merge(lease.role().name(), 1, Integer::sum);
            }
            report.append("incident=").append(idField.get(incident))
                    .append(" map=").append(map.getId()).append(" activeLimit=").append(activeLimitField.getInt(incident))
                    .append(" awareness=").append(states).append(" leases=").append(roles)
                    .append(" monsters=").append(map.getAllMonsters().stream().map(m -> m.getId()+":"+m.getHp()).toList())
                    .append('\n');
        }
        Path folder = Path.of("logs/event-capacity");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(name + ".txt"), report.toString());
    }
}

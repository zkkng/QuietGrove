package server.events.gm;

import constants.id.MapId;
import java.util.List;
import java.util.Map;

/** Curated definitions. Development host availability is distinct from verified player approval. */
public record EventDefinition(String key, int version, int lobbyMapId, List<Integer> mapIds,
                              int rounds, long roundMillis, int rewardItemId,
                              boolean playerApproved, String unavailableReason) {
    public EventDefinition { mapIds = List.copyOf(mapIds); }
    public boolean available() { return unavailableReason.isEmpty(); }
    private static final Map<String, EventDefinition> DEFINITIONS = Map.of(
        "ox", new EventDefinition("ox", 1, MapId.EVENT_OX_QUIZ, List.of(MapId.EVENT_OX_QUIZ),
                10, 30_000, 4031019, false, ""),
        "ola", new EventDefinition("ola", 1, MapId.EVENT_OLA_OLA_0,
                List.of(109030001,109030002,109030003), 1, 360_000, 4031019, false, ""),
        "fitness", new EventDefinition("fitness", 1, MapId.EVENT_PHYSICAL_FITNESS,
                List.of(109040000,109040001,109040002,109040003,109040004), 1, 900_000, 4031019, false, ""),
        "coconut", new EventDefinition("coconut", 1, MapId.EVENT_COCONUT_HARVEST,
                List.of(MapId.EVENT_COCONUT_HARVEST), 1, 300_000, 4031019, false, ""),
        "snowball", new EventDefinition("snowball", 1, MapId.EVENT_SNOWBALL_ENTRANCE,
                List.of(MapId.EVENT_SNOWBALL_ENTRANCE,MapId.EVENT_SNOWBALL), 1, 600_000, 4031019, false, ""),
        "treasure", new EventDefinition("treasure",1,MapId.EVENT_FIND_THE_JEWEL,
                java.util.stream.IntStream.concat(java.util.stream.IntStream.of(109010000,109010100,109010200),
                    java.util.stream.IntStream.concat(java.util.stream.IntStream.rangeClosed(109010101,109010110),
                        java.util.stream.IntStream.rangeClosed(109010201,109010206))).boxed().toList(),
                1,600_000,4031019,false,""));
    private static EventDefinition disabled(String key, int map, String reason) {
        return new EventDefinition(key, 1, map, List.of(map), 0, 0, 0, false, reason);
    }
    public static EventDefinition find(String key) { return DEFINITIONS.get(key.toLowerCase(java.util.Locale.ROOT)); }
    public static EventDefinition forMap(int mapId) {
        return DEFINITIONS.values().stream().filter(d -> d.lobbyMapId() == mapId).findFirst().orElse(null);
    }
    public static List<EventDefinition> catalog() {
        return DEFINITIONS.values().stream().sorted(java.util.Comparator.comparing(EventDefinition::key)).toList();
    }
}

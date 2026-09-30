package server.trainer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** A shared conversation budget: one witness per incident, with silent/emote responses too. */
final class TrainerReactionPolicy {
    enum Kind {
        FLIGHT(600_000, 35), FMA(180_000, 75), VAC(180_000, 60), LOOT(120_000, 90);
        final long cooldown;
        final int speechPercent;
        Kind(long cooldown, int speechPercent) {
            this.cooldown = cooldown;
            this.speechPercent = speechPercent;
        }
    }
    record Decision(int botId, boolean speak) { }
    private record Incident(int actorId, Kind kind) { }
    private static final int LIMIT = 4096;
    private final Map<Object, Long> maps = new LinkedHashMap<>();
    private final Map<Integer, Long> actors = new LinkedHashMap<>();
    private final Map<Integer, Long> bots = new LinkedHashMap<>();
    private final Map<Incident, Long> incidents = new LinkedHashMap<>();
    private final Map<Object, ArrayDeque<String>> recentLines = new LinkedHashMap<>();

    synchronized Decision reserve(Object map, int actorId, Kind kind, List<Integer> candidates,
                                  long now, RandomGenerator random) {
        Incident incident = new Incident(actorId, kind);
        if (cooling(maps.get(map), now, 45_000) || cooling(actors.get(actorId), now, 90_000)
                || cooling(incidents.get(incident), now, kind.cooldown)) return null;
        List<Integer> ready = candidates.stream().distinct()
                .filter(id -> !cooling(bots.get(id), now, 120_000)).limit(4).toList();
        if (ready.isEmpty()) return null;
        int witness = ready.get(random.nextInt(ready.size()));
        remember(maps, map, now);
        remember(actors, actorId, now);
        remember(bots, witness, now);
        remember(incidents, incident, now);
        return new Decision(witness, random.nextInt(100) < kind.speechPercent);
    }

    synchronized String line(Object map, List<String> choices, RandomGenerator random) {
        ArrayDeque<String> recent = recentLines.get(map);
        if (recent == null) {
            recent = new ArrayDeque<>();
            remember(recentLines, map, recent);
        }
        List<String> available = new ArrayList<>(choices);
        available.removeAll(recent);
        if (available.isEmpty()) available.addAll(choices);
        String chosen = available.get(random.nextInt(available.size()));
        recent.addLast(chosen);
        while (recent.size() > 6) recent.removeFirst();
        return chosen;
    }

    private static boolean cooling(Long previous, long now, long delay) {
        return previous != null && now - previous < delay;
    }

    private static <K, V> void remember(Map<K, V> entries, K key, V value) {
        entries.remove(key);
        entries.put(key, value);
        while (entries.size() > LIMIT) entries.remove(entries.keySet().iterator().next());
    }
}

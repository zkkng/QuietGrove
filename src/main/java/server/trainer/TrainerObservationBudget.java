package server.trainer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Memory sampling is independent of who gets a dialogue slot. Actual item losses bypass this. */
final class TrainerObservationBudget {
    private record Key(Object map, int actor, TrainerReactionPolicy.Kind kind) { }
    private final Map<Key, Long> windows = new LinkedHashMap<>();
    private static final int LIMIT = 4096;
    private static final long WINDOW_MS = 30_000;

    synchronized boolean admit(Object map, int actor, TrainerReactionPolicy.Kind kind, long now) {
        if (map == null || actor <= 0 || kind == null || now < 0) return false;
        Key key = new Key(map, actor, kind);
        Long last = windows.get(key);
        if (last != null && now - last < WINDOW_MS) return false;
        windows.entrySet().removeIf(e -> now - e.getValue() >= WINDOW_MS);
        if (windows.size() >= LIMIT) return false;
        windows.put(key, now);
        return true;
    }
}

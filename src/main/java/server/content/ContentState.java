package server.content;

import java.util.Map;
import java.util.TreeMap;

/** Persistent earned counters/timestamps. Saved with the character's inventory transaction. */
public final class ContentState {
    private final Map<String, Long> values = new TreeMap<>();
    public synchronized long get(String key) { return values.getOrDefault(key, 0L); }
    public synchronized void set(String key, long value) {
        if (!key.matches("[A-Za-z0-9_.-]{1,100}")) throw new IllegalArgumentException("Invalid progress key");
        values.put(key, value);
    }
    public synchronized long add(String key, long amount) {
        long value = Math.addExact(get(key), amount); set(key, value); return value;
    }
    public synchronized void removePrefix(String prefix) { values.keySet().removeIf(k -> k.startsWith(prefix)); }
    public synchronized Map<String, Long> snapshot() { return Map.copyOf(values); }
    public synchronized String encode() {
        StringBuilder out = new StringBuilder("v1\n");
        values.forEach((k,v) -> out.append(k).append('=').append(v).append('\n'));
        return out.toString();
    }
    public static ContentState decode(String encoded) {
        if (encoded == null || !encoded.startsWith("v1\n")) throw new IllegalArgumentException("Invalid progress version");
        ContentState state = new ContentState();
        for (String line : encoded.substring(3).split("\n")) {
            if (line.isEmpty()) continue;
            String[] pair = line.split("=", -1);
            if (pair.length != 2 || state.values.containsKey(pair[0])) throw new IllegalArgumentException("Invalid progress row");
            state.set(pair[0], Long.parseLong(pair[1]));
        }
        return state;
    }
}

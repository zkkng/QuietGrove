package server.statistics;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Worker-owned sparse deltas. Atomically accepts all seven projections or none. */
public final class StatisticsAccumulator implements BoundedStatisticsRecorder.Consumer {
    public static final int TOTAL = 0, ENTITY = 1, REGION = 2, ENTITY_REASON = 3, ENTITY_METHOD = 4;
    public static final long LIFETIME = -1;
    public static final BigInteger MAX_VALUE = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);
    public record Key(int metric, int projection, int world, int population, int assistance,
                      int entity, int region, int reason, int method, long day) implements Comparable<Key> {
        public Key {
            if (!StatisticsMetric.valid(metric) || projection < TOTAL || projection > ENTITY_METHOD
                    || world < 0 || population < 0 || population > 4 || assistance < 0 || assistance > 2
                    || entity < 0 || region < 0 || reason < 0 || reason > 255
                    || method < 0 || method > 255 || day < LIFETIME)
                throw new IllegalArgumentException("Invalid aggregate key");
        }
        @Override public int compareTo(Key other) {
            int[] left = {metric, projection, world, population, assistance, entity, region, reason, method};
            int[] right = {other.metric, other.projection, other.world, other.population, other.assistance,
                    other.entity, other.region, other.reason, other.method};
            for (int i = 0; i < left.length; i++) {
                int comparison = Integer.compare(left[i], right[i]);
                if (comparison != 0) return comparison;
            }
            return Long.compare(day, other.day);
        }
    }
    public record Delta(Key key, BigInteger value) {
        public Delta {
            if (key == null || value == null || value.signum() <= 0 || value.compareTo(MAX_VALUE) > 0)
                throw new IllegalArgumentException("Invalid delta");
        }
    }
    private final int keyLimit;
    private final Map<Key, BigInteger> values = new HashMap<>();
    private long droppedFacts, acceptedFacts, firstAt = Long.MAX_VALUE, lastAt;

    public StatisticsAccumulator(int keyLimit) {
        if (keyLimit < 7 || keyLimit > 100_000) throw new IllegalArgumentException("Key capacity [7,100000]");
        this.keyLimit = keyLimit;
    }

    @Override public void accept(int metric, int world, int population, int assistance, int entity, int region,
                                 int reason, int method, long occurredAt, long amount) {
        if (!StatisticsMetric.valid(metric) || !StatisticsDimensions.valid(world, population, assistance,
                entity, region, reason, method, occurredAt, amount)) throw new IllegalArgumentException("Invalid fact");
        long day = occurredAt / 86_400_000L;
        Key[] keys = {
                new Key(metric, TOTAL, world, population, assistance, 0, 0, 0, 0, LIFETIME),
                new Key(metric, TOTAL, world, population, assistance, 0, 0, 0, 0, day),
                new Key(metric, ENTITY, world, population, assistance, entity, 0, 0, 0, LIFETIME),
                new Key(metric, ENTITY, world, population, assistance, entity, 0, 0, 0, day),
                new Key(metric, REGION, world, population, assistance, 0, region, 0, 0, day),
                new Key(metric, ENTITY_REASON, world, population, assistance, entity, 0, reason, 0, day),
                new Key(metric, ENTITY_METHOD, world, population, assistance, entity, 0, 0, method, day)
        };
        int missing = 0;
        BigInteger[] next = new BigInteger[keys.length];
        BigInteger increment = BigInteger.valueOf(amount);
        for (int i = 0; i < keys.length; i++) {
            BigInteger old = values.get(keys[i]);
            if (old == null) missing++;
            next[i] = (old == null ? BigInteger.ZERO : old).add(increment);
            if (next[i].compareTo(MAX_VALUE) > 0) { droppedFacts++; return; }
        }
        if (values.size() + missing > keyLimit) { droppedFacts++; return; }
        for (int i = 0; i < keys.length; i++) values.put(keys[i], next[i]);
        acceptedFacts++;
        firstAt = Math.min(firstAt, occurredAt); lastAt = Math.max(lastAt, occurredAt);
    }

    /** Freeze up to 1000 rows. Caller must retain the returned batch until journal sync. */
    public List<Delta> take(int maximumRows) {
        if (maximumRows < 1 || maximumRows > 1000) throw new IllegalArgumentException("Batch rows [1,1000]");
        List<Key> sorted = new ArrayList<>(values.keySet());
        sorted.sort(Key::compareTo);
        List<Delta> deltas = new ArrayList<>(Math.min(maximumRows, sorted.size()));
        for (int i = 0; i < sorted.size() && i < maximumRows; i++) {
            Key key = sorted.get(i);
            deltas.add(new Delta(key, values.remove(key)));
        }
        return List.copyOf(deltas);
    }
    public int size() { return values.size(); }
    public long droppedFacts() { return droppedFacts; }
    public long acceptedFacts() { return acceptedFacts; }
    public long firstAt() { return firstAt == Long.MAX_VALUE ? 0 : firstAt; }
    public long lastAt() { return lastAt; }
}

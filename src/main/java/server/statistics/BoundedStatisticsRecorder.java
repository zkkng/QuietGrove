package server.statistics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Bounded multiple-producer/single-consumer primitive ring. No entity references,
 * locks, I/O, strings or per-offer allocation. Domain adapters own deduplication.
 * At most eight reservation CAS attempts; contention rejection is a coverage gap.
 */
public final class BoundedStatisticsRecorder {
    @FunctionalInterface
    public interface Consumer {
        void accept(int metric, int world, int population, int assistance, int entity, int region,
                    int reason, int method, long occurredAt, long amount);
    }

    public record Health(long accepted, long fullRejected, long contentionRejected,
                         long invalidRejected, long consumerFailures, long queued,
                         long[] rejectedByMetric) {}

    private final int capacity, mask;
    private final AtomicLongArray sequences;
    private final AtomicLong tail = new AtomicLong(), head = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong(), full = new AtomicLong(),
            contention = new AtomicLong(), invalid = new AtomicLong(), failures = new AtomicLong();
    private final AtomicLongArray rejected = new AtomicLongArray(StatisticsMetric.COUNT + 1);
    private final int[] metric, world, population, assistance, entity, region, reason, method;
    private final long[] occurredAt, amount;
    private volatile boolean enabled;

    public BoundedStatisticsRecorder(int capacity) {
        if (capacity < 2 || capacity > 65_536 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("Capacity must be a power of two in [2,65536]");
        this.capacity = capacity; mask = capacity - 1;
        sequences = new AtomicLongArray(capacity);
        metric = new int[capacity]; world = new int[capacity]; population = new int[capacity];
        assistance = new int[capacity]; entity = new int[capacity]; region = new int[capacity];
        reason = new int[capacity]; method = new int[capacity];
        occurredAt = new long[capacity]; amount = new long[capacity];
        for (int i = 0; i < capacity; i++) sequences.set(i, i);
    }

    /** Disabled by default. The integration owner enables only after source/migration gates. */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public int capacity() { return capacity; }
    public long estimatedPayloadBytes() { return (long) capacity * (8 * Integer.BYTES + 3 * Long.BYTES); }

    public boolean offer(int metricId, int worldId, int populationId, int assistanceId,
                         int entityId, int regionId, int reasonId, int methodId,
                         long eventTime, long units) {
        if (!enabled) return false;
        if (!StatisticsMetric.valid(metricId) || !StatisticsDimensions.valid(worldId, populationId,
                assistanceId, entityId, regionId, reasonId, methodId, eventTime, units)) {
            invalid.incrementAndGet();
            if (StatisticsMetric.valid(metricId)) rejected.incrementAndGet(metricId);
            return false;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            long position = tail.get();
            int slot = (int) position & mask;
            long difference = sequences.get(slot) - position;
            if (difference < 0) { full.incrementAndGet(); rejected.incrementAndGet(metricId); return false; }
            if (difference != 0 || !tail.compareAndSet(position, position + 1)) continue;
            metric[slot] = metricId; world[slot] = worldId; population[slot] = populationId;
            assistance[slot] = assistanceId; entity[slot] = entityId; region[slot] = regionId;
            reason[slot] = reasonId; method[slot] = methodId; occurredAt[slot] = eventTime; amount[slot] = units;
            accepted.incrementAndGet();
            sequences.set(slot, position + 1); // release publishes all primitive fields
            return true;
        }
        contention.incrementAndGet(); rejected.incrementAndGet(metricId); return false;
    }

    /** Called by exactly one worker. A callback failure releases its slot and is visible. */
    public int drain(Consumer consumer, int limit) {
        if (consumer == null || limit < 0) throw new IllegalArgumentException("Invalid drain");
        int consumed = 0;
        while (consumed < limit) {
            long position = head.get();
            int slot = (int) position & mask;
            if (sequences.get(slot) != position + 1) break; // producer may be preempted
            try {
                consumer.accept(metric[slot], world[slot], population[slot], assistance[slot], entity[slot],
                        region[slot], reason[slot], method[slot], occurredAt[slot], amount[slot]);
            } catch (RuntimeException exception) {
                failures.incrementAndGet();
                rejected.incrementAndGet(metric[slot]);
                throw exception;
            } finally {
                sequences.set(slot, position + capacity);
                head.set(position + 1);
            }
            consumed++;
        }
        return consumed;
    }

    public Health health() {
        long[] byMetric = new long[StatisticsMetric.COUNT + 1];
        for (int i = 1; i < byMetric.length; i++) byMetric[i] = rejected.get(i);
        return new Health(accepted.get(), full.get(), contention.get(), invalid.get(), failures.get(),
                Math.min(capacity, Math.max(0, tail.get() - head.get())), byMetric);
    }
}

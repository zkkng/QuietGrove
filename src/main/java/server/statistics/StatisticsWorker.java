package server.statistics;

import java.io.IOException;
import java.util.UUID;

/**
 * Explicit worker-side pump, independent of game timers/config/startup.
 * Integration supplies a dedicated background loop and sink; no auto-enablement.
 * A frozen pending batch is retained across sink failures and never re-added to deltas.
 */
public final class StatisticsWorker {
    private final BoundedStatisticsRecorder recorder;
    private final StatisticsAccumulator accumulator;
    private final StatisticsJournal journal;
    private final StatisticsBatchSink sink;
    private final String epoch;
    private final UUID producer, boot;
    private long nextSequence = 1, journaledBatches, committedBatches, duplicateBatches, lastDurableAt;
    private StatisticsBatch pending;
    private boolean pendingDurable;

    public StatisticsWorker(BoundedStatisticsRecorder recorder, StatisticsAccumulator accumulator,
                            StatisticsJournal journal, StatisticsBatchSink sink, String epoch, UUID producer) {
        if (recorder == null || accumulator == null || journal == null || sink == null || producer == null
                || epoch == null || !epoch.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IllegalArgumentException("Worker dependencies");
        this.recorder = recorder; this.accumulator = accumulator; this.journal = journal;
        this.sink = sink; this.epoch = epoch; this.producer = producer; boot = UUID.randomUUID();
    }

    public int drain(int maximumFacts) { return recorder.drain(accumulator, maximumFacts); }

    /** Streams old durable records through the idempotent sink before enabling capture. */
    public void replay() throws IOException {
        if (recorder.isEnabled()) throw new IllegalStateException("Disable capture before replay");
        journal.replay(batch -> {
            if (!batch.epoch().equals(epoch) || !batch.producer().equals(producer))
                throw new IOException("Journal producer/epoch does not match worker");
            apply(batch);
            lastDurableAt = Math.max(lastDurableAt, batch.lastAt());
        });
    }

    /** Flushes one bounded batch. Retry the same method on recoverable sink failure. */
    public boolean flushOne() throws IOException {
        if (pending == null) {
            if (accumulator.size() == 0) return false;
            pending = new StatisticsBatch(epoch, producer, boot, nextSequence++,
                    accumulator.firstAt(), accumulator.lastAt(), accumulator.take(StatisticsBatch.MAX_ROWS));
        }
        if (!pendingDurable) {
            journal.appendAndSync(pending);
            pendingDurable = true; journaledBatches++;
            lastDurableAt = Math.max(lastDurableAt, pending.lastAt());
        }
        apply(pending); // exception preserves pending identity and durability flag
        pending = null; pendingDurable = false;
        return true;
    }

    /** Final worker-only drain journals remaining deltas without waiting for database. */
    public void persistRemainingForShutdown() throws IOException {
        if (pending != null) {
            if (!pendingDurable) journal.appendAndSync(pending);
            pending = null; pendingDurable = false;
        }
        while (accumulator.size() > 0) {
            StatisticsBatch batch = new StatisticsBatch(epoch, producer, boot, nextSequence++,
                    accumulator.firstAt(), accumulator.lastAt(), accumulator.take(StatisticsBatch.MAX_ROWS));
            journal.appendAndSync(batch);
            lastDurableAt = Math.max(lastDurableAt, batch.lastAt());
        }
    }
    private void apply(StatisticsBatch batch) throws IOException {
        if (sink.apply(batch)) committedBatches++; else duplicateBatches++;
    }
    public boolean hasPendingBatch() { return pending != null; }
    public long journaledBatches() { return journaledBatches; }
    public long committedBatches() { return committedBatches; }
    public long duplicateBatches() { return duplicateBatches; }
    /** Informational only: capture gaps/repaired tails still make coverage partial. */
    public long lastDurableAt() { return lastDurableAt; }
}

package server.statistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsWorkerTest {
    @TempDir Path directory;
    static final class Sink implements StatisticsBatchSink {
        final Map<String,String> receipts = new HashMap<>();
        final Map<StatisticsAccumulator.Key,BigInteger> values = new HashMap<>();
        boolean failBefore, failAfter;
        @Override public boolean apply(StatisticsBatch batch) throws IOException {
            String id = batch.producer()+":"+batch.boot()+":"+batch.sequence();
            if (receipts.containsKey(id)) {
                if (!receipts.get(id).equals(batch.checksum())) throw new IOException("Checksum mismatch");
                return false;
            }
            if (failBefore) { failBefore=false; throw new IOException("Database down"); }
            for (var delta : batch.deltas()) values.merge(delta.key(),delta.value(),BigInteger::add);
            receipts.put(id,batch.checksum());
            if (failAfter) { failAfter=false; throw new IOException("Lost commit acknowledgement"); }
            return true;
        }
        BigInteger total() { return values.entrySet().stream().filter(e->e.getKey().projection()==0&&e.getKey().day()==-1)
                .map(Map.Entry::getValue).reduce(BigInteger.ZERO,BigInteger::add); }
    }
    static void offer(BoundedStatisticsRecorder recorder) {
        recorder.setEnabled(true); assertTrue(recorder.offer(1,0,0,0,2000000,1,1,1,0,3));
    }
    @Test void databaseFailureRetainsPendingIdentityAndDoesNotJournalAgain() throws Exception {
        var recorder = new BoundedStatisticsRecorder(8); var sink = new Sink(); sink.failBefore=true;
        try (var journal = new StatisticsJournal(directory.resolve("spool"),8192)) {
            var worker = new StatisticsWorker(recorder,new StatisticsAccumulator(100),journal,sink,"test",UUID.randomUUID());
            offer(recorder); worker.drain(10);
            assertThrows(IOException.class,worker::flushOne); long size = journal.size();
            assertTrue(worker.hasPendingBatch()); assertEquals(BigInteger.ZERO,sink.total());
            assertTrue(worker.flushOne()); assertEquals(size,journal.size()); assertEquals(BigInteger.valueOf(3),sink.total());
        }
    }
    @Test void uncertainCommitAcknowledgementRetriesWithoutDuplicateCounts() throws Exception {
        var recorder = new BoundedStatisticsRecorder(8); var sink = new Sink(); sink.failAfter=true;
        try (var journal = new StatisticsJournal(directory.resolve("spool"),8192)) {
            var worker = new StatisticsWorker(recorder,new StatisticsAccumulator(100),journal,sink,"test",UUID.randomUUID());
            offer(recorder); worker.drain(10); assertThrows(IOException.class,worker::flushOne);
            assertTrue(worker.flushOne()); assertEquals(1,worker.duplicateBatches()); assertEquals(BigInteger.valueOf(3),sink.total());
        }
    }
    @Test void rebootReplaysDurableFactsBeforeCaptureAndRejectsWrongProducer() throws Exception {
        var recorder = new BoundedStatisticsRecorder(8); var sink = new Sink(); UUID producer = UUID.randomUUID();
        Path path = directory.resolve("spool");
        try (var journal = new StatisticsJournal(path,8192)) {
            var worker = new StatisticsWorker(recorder,new StatisticsAccumulator(100),journal,sink,"test",producer);
            offer(recorder); worker.drain(10); worker.flushOne();
        }
        try (var journal = new StatisticsJournal(path,8192)) {
            var freshSink = new Sink();
            var worker = new StatisticsWorker(new BoundedStatisticsRecorder(8),new StatisticsAccumulator(100),journal,freshSink,"test",producer);
            worker.replay(); assertEquals(BigInteger.valueOf(3),freshSink.total());
            worker.replay(); assertEquals(BigInteger.valueOf(3),freshSink.total());
            var wrong = new StatisticsWorker(new BoundedStatisticsRecorder(8),new StatisticsAccumulator(100),journal,freshSink,"test",UUID.randomUUID());
            assertThrows(IOException.class,wrong::replay);
        }
    }
    @Test void enabledCaptureCannotRaceJournalReplay() throws Exception {
        var recorder = new BoundedStatisticsRecorder(8); recorder.setEnabled(true);
        try (var journal = new StatisticsJournal(directory.resolve("spool"),8192)) {
            var worker = new StatisticsWorker(recorder,new StatisticsAccumulator(100),journal,new Sink(),"test",UUID.randomUUID());
            assertThrows(IllegalStateException.class,worker::replay); assertFalse(worker.flushOne());
        }
    }
}

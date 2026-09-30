package server.statistics;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedStatisticsRecorderTest {
    private static boolean offer(BoundedStatisticsRecorder recorder, int entity, long amount) {
        return recorder.offer(1, 0, 0, 0, entity, 9, 1, 1, 1_790_726_400_000L, amount);
    }
    @Test void boundedConfigurationAndDisabledDefault() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedStatisticsRecorder(3));
        var recorder = new BoundedStatisticsRecorder(8);
        assertFalse(offer(recorder, 2000000, 1));
        assertEquals(0, recorder.health().accepted());
        assertEquals(448, recorder.estimatedPayloadBytes());
    }
    @Test void invalidQuantityAndIdentityCannotEnterQueue() {
        var recorder = new BoundedStatisticsRecorder(8); recorder.setEnabled(true);
        assertFalse(offer(recorder, 2000000, 0)); assertFalse(offer(recorder, 2000000, -1));
        assertFalse(recorder.offer(99, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        assertEquals(3, recorder.health().invalidRejected()); assertEquals(0, recorder.health().queued());
    }
    @Test void fullQueueRejectsWithoutOverwritingAcceptedFactsAndWrapsSafely() {
        var recorder = new BoundedStatisticsRecorder(2); recorder.setEnabled(true);
        assertTrue(offer(recorder, 1, 10)); assertTrue(offer(recorder, 2, 20)); assertFalse(offer(recorder, 3, 30));
        List<Integer> entities = new ArrayList<>();
        recorder.drain((m,w,p,a,e,r,s,h,t,n)->entities.add(e), 1);
        assertTrue(offer(recorder, 4, 40));
        recorder.drain((m,w,p,a,e,r,s,h,t,n)->entities.add(e), 10);
        assertEquals(List.of(1,2,4), entities); assertEquals(1, recorder.health().fullRejected());
        assertEquals(0, recorder.health().queued());
    }
    @Test void consumerFailureIsVisibleAndReleasesFailedSlot() {
        var recorder = new BoundedStatisticsRecorder(2); recorder.setEnabled(true);
        offer(recorder, 1, 1); offer(recorder, 2, 2);
        assertThrows(IllegalStateException.class, () -> recorder.drain((m,w,p,a,e,r,s,h,t,n)->{throw new IllegalStateException();},1));
        List<Integer> seen = new ArrayList<>();
        assertEquals(1, recorder.drain((m,w,p,a,e,r,s,h,t,n)->seen.add(e), 2));
        assertEquals(List.of(2), seen); assertEquals(1, recorder.health().consumerFailures());
    }
    @Test void concurrentProducersPublishConsistentFieldsAndAccountForEveryRejection() throws Exception {
        var recorder = new BoundedStatisticsRecorder(65_536); recorder.setEnabled(true);
        int workers = 4, each = 2000;
        boolean[] accepted = new boolean[workers*each];
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                int offset = worker * each;
                futures.add(pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException exception) { throw new RuntimeException(exception); }
                    for (int i = 0; i < each; i++) accepted[offset+i] = offer(recorder, offset+i+1, offset+i+1L);
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(10,TimeUnit.SECONDS);
            Set<Integer> seen = new HashSet<>();
            recorder.drain((m,w,p,a,e,r,s,h,t,n)-> {
                assertEquals(1,m); assertEquals(9,r); assertEquals(e,n);
                assertEquals(1,s); assertEquals(1,h); assertTrue(accepted[e-1]); assertTrue(seen.add(e));
            }, workers*each);
            long expected = 0; for (boolean result : accepted) if (result) expected++;
            assertEquals(expected,seen.size()); assertEquals(expected,recorder.health().accepted());
            assertEquals(workers*each,expected+recorder.health().contentionRejected()+recorder.health().fullRejected());
        } finally { pool.shutdownNow(); }
    }
    @Test void quantityIsPreservedAboveJavaScriptIntegerPrecision() {
        var recorder = new BoundedStatisticsRecorder(2); recorder.setEnabled(true);
        assertTrue(offer(recorder,2000000,9_007_199_254_741_001L));
        recorder.drain((m,w,p,a,e,r,s,h,t,n)->assertEquals(9_007_199_254_741_001L,n),1);
    }
    @Test void projectionsPartitionByEntityAndDoNotMultiplyWorldCounts() {
        var accumulator = new StatisticsAccumulator(100);
        accumulator.accept(1,0,0,0,2000000,9,1,1,0,3);
        accumulator.accept(1,0,0,0,2060000,9,2,1,0,20);
        var rows = accumulator.take(100);
        BigInteger total = rows.stream().filter(d->d.key().projection()==StatisticsAccumulator.TOTAL && d.key().day()==-1)
                .map(StatisticsAccumulator.Delta::value).reduce(BigInteger.ZERO,BigInteger::add);
        BigInteger entities = rows.stream().filter(d->d.key().projection()==StatisticsAccumulator.ENTITY && d.key().day()==-1)
                .map(StatisticsAccumulator.Delta::value).reduce(BigInteger.ZERO,BigInteger::add);
        assertEquals(BigInteger.valueOf(23),total); assertEquals(total,entities);
    }
    @Test void utcBoundaryUsesOccurrenceTimeRatherThanFlushTime() {
        var accumulator = new StatisticsAccumulator(100);
        accumulator.accept(2,0,0,0,100100,1,0,0,86_399_999,1);
        accumulator.accept(2,0,0,0,100100,1,0,0,86_400_000,1);
        var rows = accumulator.take(100);
        assertEquals(Set.of(-1L,0L,1L),new HashSet<>(rows.stream().filter(d->d.key().projection()==0).map(d->d.key().day()).toList()));
        assertEquals(BigInteger.TWO,rows.stream().filter(d->d.key().projection()==0&&d.key().day()==-1).findFirst().orElseThrow().value());
    }
    @Test void keySaturationRejectsEntireFactAndKeepsExistingTotals() {
        var accumulator = new StatisticsAccumulator(7);
        accumulator.accept(1,0,0,0,1,1,1,1,0,10);
        accumulator.accept(1,0,0,0,2,1,1,1,0,20);
        assertEquals(1,accumulator.droppedFacts()); assertEquals(1,accumulator.acceptedFacts());
        assertEquals(7,accumulator.size());
        assertTrue(accumulator.take(100).stream().allMatch(d->d.value().equals(BigInteger.TEN)));
    }
    @Test void workerTotalsExceedLongWithoutWrappingAndFreezeIsBounded() {
        var accumulator = new StatisticsAccumulator(100);
        accumulator.accept(1,0,0,0,1,1,1,1,0,Long.MAX_VALUE);
        accumulator.accept(1,0,0,0,1,1,1,1,0,Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->accumulator.take(1001));
        var first = accumulator.take(2);
        assertEquals(2,first.size()); assertEquals(5,accumulator.size());
        assertTrue(first.stream().allMatch(d->d.value().equals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO))));
    }

    @Test void simultaneousDrainAndProducersWrapWithoutTornFieldsOrDuplicateFacts() throws Exception {
        var recorder = new BoundedStatisticsRecorder(64); recorder.setEnabled(true);
        int workers=4, each=3000;
        boolean[] accepted=new boolean[workers*each];
        Set<Integer> seen=new HashSet<>();
        var done=new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService pool=Executors.newFixedThreadPool(workers+1);
        CountDownLatch start=new CountDownLatch(1);
        try {
            var reader=pool.submit(()-> {
                try { start.await(); } catch(InterruptedException exception) { throw new RuntimeException(exception); }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(done.get()<workers || recorder.health().queued()>0) {
                    recorder.drain((m,w,p,a,e,r,s,h,t,n)-> {
                        assertEquals(e,n); assertEquals(9,r); assertEquals(1,s); assertTrue(seen.add(e));
                    },64);
                    if(System.nanoTime()>deadline) throw new AssertionError("Recorder did not drain in time");
                    Thread.onSpinWait();
                }
            });
            List<Future<?>> writers=new ArrayList<>();
            for(int worker=0;worker<workers;worker++) {
                int offset=worker*each;
                writers.add(pool.submit(()-> {
                    try {
                        start.await();
                        for(int i=0;i<each;i++) accepted[offset+i]=offer(recorder,offset+i+1,offset+i+1L);
                    } catch(InterruptedException exception) { throw new RuntimeException(exception); }
                    finally { done.incrementAndGet(); }
                }));
            }
            start.countDown();
            for(var writer:writers) writer.get(10,TimeUnit.SECONDS);
            reader.get(10,TimeUnit.SECONDS);
            long count=0;
            for(int i=0;i<accepted.length;i++) { if(accepted[i]) count++; assertEquals(accepted[i],seen.contains(i+1)); }
            assertEquals(count,recorder.health().accepted());
            assertEquals(workers*each,count+recorder.health().fullRejected()+recorder.health().contentionRejected());
        } finally { pool.shutdownNow(); }
    }
}

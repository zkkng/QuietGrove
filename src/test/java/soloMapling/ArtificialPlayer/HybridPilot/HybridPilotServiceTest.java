package soloMapling.ArtificialPlayer.HybridPilot;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridPilotServiceTest {
    final Character owner = mock(Character.class);
    final AtomicInteger created = new AtomicInteger();
    final HybridPilotService service = new HybridPilotService((map, point, name) -> {
        created.incrementAndGet();
        HybridPilotBot actor = mock(HybridPilotBot.class);
        AtomicBoolean removed = new AtomicBoolean();
        when(actor.removed()).thenAnswer(inv -> removed.get());
        when(actor.status()).thenReturn(name);
        doAnswer(inv -> { removed.set(true); return null; }).when(actor).remove();
        return actor;
    });

    @Test void startsEmptyAndInvalidCountsAllocateNothing() {
        assertTrue(service.status().getFirst().contains("0/3"));
        for (int count : new int[]{-1, 0, 4, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> service.spawn(owner, count));
        assertEquals(0, created.get());
    }
    @Test void repeatedCallsCannotExceedGlobalCapAndOffReleasesSlots() {
        assertEquals(java.util.List.of("HybridOak", "HybridAsh"), service.spawn(owner, 2));
        assertThrows(IllegalArgumentException.class, () -> service.spawn(owner, 2));
        assertEquals(java.util.List.of("HybridElm"), service.spawn(owner, 1)); assertEquals(3, created.get());
        assertThrows(IllegalArgumentException.class, () -> service.spawn(owner, 1));
        assertEquals(3, service.off()); assertEquals(0, service.off());
        assertEquals(java.util.List.of("HybridOak", "HybridAsh", "HybridElm"), service.spawn(owner, 3)); assertEquals(6, created.get());
    }
    @Test void concurrentSpawnsReserveCapacityAtomically() throws Exception {
        AtomicInteger successes = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 30).mapToObj(i -> pool.submit(() -> {
                try { service.spawn(owner, 1); successes.incrementAndGet(); }
                catch (IllegalArgumentException expected) { }
            })).toList();
            for (var future : futures) future.get();
        }
        assertEquals(3, successes.get()); assertEquals(3, created.get());
    }
    @Test void partialSpawnFailureRemovesOnlyNewBatch() {
        HybridPilotBot first = mock(HybridPilotBot.class), second = mock(HybridPilotBot.class);
        when(first.status()).thenReturn("existing");
        when(second.removed()).thenReturn(false, true);
        AtomicInteger calls = new AtomicInteger();
        HybridPilotService cohort = new HybridPilotService((map, point, name) -> switch (calls.incrementAndGet()) {
            case 1 -> first; case 2 -> second; default -> throw new IllegalStateException("spawn failure");
        });
        cohort.spawn(owner, 1);
        assertThrows(IllegalStateException.class, () -> cohort.spawn(owner, 2));
        verify(second).remove(); verify(first, never()).remove();
    }
    @Test void oneCleanupFailureDoesNotPreventOtherRemovalsAndKeepsSlotForRetry() {
        HybridPilotBot failed = mock(HybridPilotBot.class), ok = mock(HybridPilotBot.class);
        when(failed.status()).thenReturn("fault"); when(ok.status()).thenReturn("ok");
        AtomicInteger calls = new AtomicInteger();
        HybridPilotService cohort = new HybridPilotService((map, point, name) -> calls.incrementAndGet() == 1 ? failed : ok);
        cohort.spawn(owner, 2);
        when(ok.removed()).thenReturn(true);
        doThrow(new IllegalStateException("cleanup")).when(failed).remove();
        assertThrows(IllegalStateException.class, cohort::off);
        verify(ok).remove(); assertTrue(cohort.status().getFirst().contains("1/3"));
        doNothing().when(failed).remove(); when(failed.removed()).thenReturn(true);
        cohort.off(); assertTrue(cohort.status().getFirst().contains("0/3"));
    }
}

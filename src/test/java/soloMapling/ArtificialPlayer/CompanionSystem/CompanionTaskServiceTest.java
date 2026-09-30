package soloMapling.ArtificialPlayer.CompanionSystem;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService.*;

class CompanionTaskServiceTest {
    private final AtomicLong clock = new AtomicLong(1000);
    private final CompanionTaskService service = new CompanionTaskService(clock::get, 30);
    private final PartyKey party = new PartyKey(0, 1);
    private final PriorActivity prior = new PriorActivity("TrainingBot", 100, 101);
    @Test void pendingOwnReservationRemainsEligibleButEveryOtherLeaseIsBusy() {
        var token = service.reserve(1, 10, party, 1, 1, 1, 2000, prior).orElseThrow();
        assertFalse(service.ownedByOther(10, party, 1));
        assertTrue(service.ownedByOther(10, party, 2));
        assertTrue(service.ownedByOther(10, new PartyKey(0,2), 1));
        service.commit(token, () -> true).orElseThrow();
        assertTrue(service.ownedByOther(10, party, 1));
    }

    @Test
    void capacityCountsPendingAndCommittedOwnershipAndExpiresExactlyOnDeadline() {
        for (int bot = 10; bot < 15; bot++) assertTrue(service.reserve(1, bot, party, 1, 1, 1, 2000, prior).isPresent());
        assertTrue(service.reserve(1, 15, party, 1, 1, 1, 2000, prior).isEmpty());
        assertEquals(5, service.pendingSeats(party));
        clock.set(1999);
        assertEquals(5, service.occupiedCapacity());
        clock.set(2000);
        assertEquals(0, service.occupiedCapacity());
    }

    @Test
    void failedJoinAndExceptionKeepOriginalActivityAndReleaseReservation() {
        var token = service.reserve(1, 10, party, 1, 1, 1, 2000, prior).orElseThrow();
        assertTrue(service.commit(token, () -> false).isEmpty());
        assertFalse(service.owned(10));
        token = service.reserve(2, 10, party, 1, 1, 1, 2000, prior).orElseThrow();
        var finalToken = token;
        assertThrows(IllegalStateException.class, () -> service.commit(finalToken, () -> { throw new IllegalStateException(); }));
        assertFalse(service.owned(10));
        assertTrue(service.tasks().isEmpty());
    }

    @Test
    void taskCreatedOnlyAfterJoinAndStaleGenerationCannotRestoreOrTransition() {
        var token = service.reserve(1, 10, party, 1, 1, 1, 2000, prior).orElseThrow();
        var task = service.commit(token, () -> {
            assertTrue(service.task(10).isEmpty());
            return true;
        }).orElseThrow();
        assertEquals(prior, task.prior());
        assertEquals(State.FOLLOW, task.state());
        assertTrue(service.transition(10, task.generation(), 2, State.SUPPORT).isPresent());
        assertEquals(2, service.task(10).orElseThrow().leaderId());
        assertEquals(1, service.task(10).orElseThrow().ownerId());
        assertTrue(service.release(10, task.generation()).isPresent());
        assertTrue(service.release(10, task.generation()).isEmpty());
        var next = service.reserve(2, 10, party, 1, 1, 1, 2000, prior).orElseThrow();
        service.commit(next, () -> true).orElseThrow();
        assertTrue(service.release(10, task.generation()).isEmpty());
        assertTrue(service.transition(10, task.generation(), 1, State.ENGAGE).isEmpty());
        assertTrue(service.commit(token, () -> { fail("stale join must not run"); return true; }).isEmpty());
    }

    @Test
    void cancellationAndExpiryPreventLateCanonicalJoin() {
        var token = service.reserve(1, 10, party, 1, 1, 1, 2000, prior).orElseThrow();
        service.cancelRequest(1);
        assertTrue(service.commit(token, () -> { fail("cancelled join"); return true; }).isEmpty());
        token = service.reserve(2, 10, party, 1, 1, 1, 2000, prior).orElseThrow();
        clock.set(2000);
        assertTrue(service.commit(token, () -> { fail("expired join"); return true; }).isEmpty());
    }

    @Test
    void globalCapIsConfigurableAndWorldScopedPartyIdsDoNotShareSeats() {
        var small = new CompanionTaskService(clock::get, 2);
        var first = small.reserve(1, 10, party, 1, 1, 5, 2000, prior).orElseThrow();
        assertTrue(small.reserve(2, 11, new PartyKey(1, 1), 2, 1, 5, 2000, prior).isPresent());
        small.commit(first, () -> true).orElseThrow();
        assertTrue(small.reserve(3, 12, new PartyKey(0, 2), 3, 1, 1, 2000, prior).isEmpty());
        assertEquals(2, small.occupiedCapacity());
    }

    @Test
    void raceTwoHumansForOneBotAndManyBotsForLastSeat() throws Exception {
        assertEquals(1, race(true));
        assertEquals(1, race(false));
    }

    private long race(boolean sameBot) throws Exception {
        var registry = new CompanionTaskService(clock::get, 30);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Optional<Reservation>>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return registry.reserve(index + 1, sameBot ? 10 : 10 + index,
                            sameBot ? new PartyKey(0, index + 1) : party, index + 1,
                            1, sameBot ? 1 : 5, 2000, prior);
                }));
            }
            start.countDown();
            long winners = 0;
            for (var future : futures) if (future.get().isPresent()) winners++;
            assertEquals(1, registry.occupiedCapacity());
            return winners;
        }
    }
}

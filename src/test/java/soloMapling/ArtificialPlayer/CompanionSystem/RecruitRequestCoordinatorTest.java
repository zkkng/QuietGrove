package soloMapling.ArtificialPlayer.CompanionSystem;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService.*;
import static soloMapling.ArtificialPlayer.CompanionSystem.RecruitRequestCoordinator.*;

class RecruitRequestCoordinatorTest {
    private final AtomicLong clock = new AtomicLong();
    private final CompanionTaskService tasks = new CompanionTaskService(clock::get, 30);
    private final FakeGateway gateway = new FakeGateway();
    private final RecruitRequestCoordinator coordinator = new RecruitRequestCoordinator(tasks, clock::get, gateway);
    private final Context context = new Context(1, new PartyKey(0, 100), 1, 123, "party up");
    private final List<Candidate> candidates = IntStream.range(10, 110)
            .mapToObj(id -> new Candidate(id, new PriorActivity("SocialBot", 100, -1))).toList();
    @Test void channelShutdownCancelsOnlyThatChannelsQueuedJoinsAndSpeech() {
        Context other = new Context(2,new PartyKey(0,101),2,456,"party up");
        coordinator.request(context,candidates.subList(0,5));
        coordinator.request(other,candidates.subList(5,10));
        coordinator.cancelChannel(1,1); assertEquals(2,coordinator.queuedRequests());
        coordinator.cancelChannel(0,1);
        assertEquals(1,coordinator.queuedRequests()); assertEquals(0,tasks.pendingSeats(context.party()));
        assertEquals(5,tasks.pendingSeats(other.party()));
        clock.set(5000); coordinator.pump();
        assertEquals(5,gateway.messages.size());
        assertTrue(tasks.tasks().stream().allMatch(t -> t.channelId()==2));
    }

    private class FakeGateway implements Gateway {
        int members = 1;
        boolean ownerValid = true;
        boolean joinsSucceed = true;
        Set<Integer> busy = new HashSet<>();
        List<Long> messages = new ArrayList<>();
        List<Integer> joined = new ArrayList<>();
        public int memberCount(Context c) { return members; }
        public boolean validOwner(Context c) { return ownerValid; }
        public boolean eligible(Context c, int botId) { return !busy.contains(botId) && !joined.contains(botId); }
        public boolean canonicalJoin(Context c, int botId) {
            if (!joinsSucceed || members >= 6) return false;
            members++; joined.add(botId); return true;
        }
        public void accepted(Context c, Task task) { messages.add(clock.get()); }
    }

    @Test
    void hundredCandidatesProduceFiveTotalStaggeredMessagesAndNeverMoreThanSixMembers() {
        long requestId = coordinator.request(context, candidates);
        assertTrue(requestId > 0);
        assertEquals(requestId, coordinator.request(context, candidates));
        assertEquals(5, tasks.pendingSeats(context.party()));
        clock.set(FIRST_REPLY_MS - 1); coordinator.pump();
        assertTrue(gateway.messages.isEmpty());
        for (int i = 0; i < 5; i++) {
            clock.set(FIRST_REPLY_MS + REPLY_GAP_MS * i); coordinator.pump();
            assertEquals(i + 1, gateway.messages.size());
        }
        assertEquals(6, gateway.members);
        assertEquals(5, tasks.tasks().size());
        assertEquals(0, coordinator.queuedRequests());
        assertEquals(0, coordinator.request(context, candidates));
        coordinator.pump();
        assertEquals(5, gateway.messages.size());
    }

    @Test
    void laterRequestAfterTwoJoinsFillsThreeRemainingSeatsWithoutCooldown() {
        coordinator.request(context, candidates.subList(0, 2));
        clock.set(2000); coordinator.pump();
        assertEquals(3, gateway.members);
        assertTrue(coordinator.request(context, candidates) > 0);
        clock.set(5000); coordinator.pump();
        assertEquals(6, gateway.members);
        assertEquals(5, gateway.messages.size());
    }

    @Test
    void cancelAfterTwoJoinsStopsAllQueuedMessagesAndRejoins() {
        coordinator.request(context, candidates);
        clock.set(1700); coordinator.pump();
        assertEquals(2, gateway.messages.size());
        coordinator.cancel(context.ownerId(), context.party());
        clock.set(14000); coordinator.pump();
        assertEquals(2, gateway.messages.size());
        assertEquals(0, tasks.pendingSeats(context.party()));
        assertEquals(2, tasks.tasks().size());
    }

    @Test
    void expiryBusyInvalidOwnerFailedJoinAndHumanTakingLastSeatReleaseWithoutSpeech() {
        for (int scenario = 0; scenario < 5; scenario++) {
            gateway.members = 5; gateway.ownerValid = true; gateway.joinsSucceed = true; gateway.busy.clear();
            clock.set(scenario * 20_000L);
            assertTrue(coordinator.request(context, candidates) > 0);
            switch (scenario) {
                case 0 -> clock.addAndGet(EXPIRY_MS);
                case 1 -> gateway.busy.add(10);
                case 2 -> gateway.ownerValid = false;
                case 3 -> gateway.joinsSucceed = false;
                case 4 -> gateway.members = 6;
            }
            clock.addAndGet(1000); coordinator.pump();
            assertEquals(0, tasks.occupiedCapacity());
            assertEquals(0, coordinator.queuedRequests());
            assertTrue(gateway.messages.isEmpty());
        }
    }

    @Test
    void emptyOrNonleaderRequestDoesNotAccumulateHistory() {
        gateway.ownerValid = false;
        assertEquals(0, coordinator.request(context, candidates));
        gateway.ownerValid = true;
        for (int i = 0; i < 100; i++) assertEquals(0, coordinator.request(context, List.of()));
        assertEquals(0, coordinator.queuedRequests());
    }

    @Test
    void changedMapContextInvalidatesCoalescedOldReservationGeneration() {
        long old = coordinator.request(context, candidates.subList(0, 1));
        var updated = new Context(1, context.party(), 1, 124, context.fingerprint());
        long fresh = coordinator.request(updated, candidates.subList(0, 1));
        assertNotEquals(old, fresh);
        assertEquals(1, tasks.pendingSeats(context.party()));
        clock.set(1000); coordinator.pump();
        assertEquals(1, gateway.joined.size());
        assertEquals(fresh, tasks.task(10).orElseThrow().requestId());
    }
}

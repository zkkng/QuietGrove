package net.server.world;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartyCapacityTest {
    private PartyCharacter member(int id) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        return member;
    }

    @Test void simultaneousHumanAndBotAdmissionsCannotExceedSix() throws Exception {
        PartyCharacter leader = member(1);
        Party party = new Party(1, leader);
        assertTrue(party.addMember(leader));
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(16);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int id = 2; id <= 101; id++) {
                PartyCharacter candidate = member(id);
                results.add(pool.submit(() -> { gate.await(); return party.addMember(candidate); }));
            }
            gate.countDown();
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertEquals(5, accepted);
            assertEquals(6, party.getMembers().size());
            assertEquals(6, party.getMembersSortedByHistory().size());
        } finally { pool.shutdownNow(); }
    }

    @Test void duplicateIdsDoNotConsumeSeatsAndLeaveReopensExactlyOneSeat() {
        PartyCharacter leader = member(1);
        Party party = new Party(1, leader);
        assertTrue(party.addMember(leader));
        assertFalse(party.addMember(member(1)));
        assertFalse(party.addMember(null));
        for (int id = 2; id <= 6; id++) assertTrue(party.addMember(member(id)));
        assertFalse(party.addMember(member(7)));
        party.removeMember(party.getMemberById(3));
        assertTrue(party.addMember(member(7)));
        assertEquals(6, party.getMembers().size());
    }
}

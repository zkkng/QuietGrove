package server.events.gm;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class EventSessionTest {
    private final AtomicLong clock=new AtomicLong(1000);
    private EventSession session(int cap) {
        var session=new EventSession(1,EventDefinition.find("ox"),EventSession.Capacity.development(cap),clock::get,0,1,999);
        assertTrue(session.open()); return session;
    }
    @Test void hundredConcurrentJoinsReserveOnlyActualSeatsWithBalancedTeams() throws Exception {
        var session=session(7); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(12)) {
            List<Future<EventSession.Admission>> results=new ArrayList<>();
            for(int id=1;id<=100;id++) { int actor=id; results.add(pool.submit(() -> {
                start.await(); return session.reserve(actor,false,100).orElse(null);
            })); }
            start.countDown(); int accepted=0,zero=0;
            for(var result:results) { var token=result.get(); if(token!=null) {
                accepted++; if(token.team()==0) zero++; assertTrue(session.commit(token));
            } }
            assertEquals(7,accepted); assertTrue(Math.abs(zero-(accepted-zero))<=1);
            assertEquals(7,session.snapshot().humans()); assertEquals(0,session.freeSeats());
        }
    }
    @Test void expiryLeaveCloseEntryAndCancelDoNotInflateOrResurrectMembership() {
        var session=session(2); var token=session.reserve(1,false,100).orElseThrow();
        clock.addAndGet(15_000); assertFalse(session.commit(token)); assertEquals(2,session.freeSeats());
        token=session.reserve(1,false,100).orElseThrow(); assertTrue(session.commit(token));
        assertTrue(session.leave(1).isPresent()); assertTrue(session.leave(1).isEmpty());
        assertEquals(2,session.freeSeats());
        token=session.reserve(2,false,100).orElseThrow(); session.cancel();
        assertFalse(session.commit(token)); assertTrue(session.cancel().isEmpty());
        assertFalse(session.start()); assertFalse(session.running(1));
    }
    @Test void restartIdentityRejectsOldAdmissionAndGenerationChecksRejectActions() {
        var old=session(2); var token=old.reserve(1,false,100).orElseThrow(); old.cancel();
        var fresh=session(2); fresh.reserve(1,false,100).orElseThrow();
        assertFalse(fresh.commit(token));
        var current=fresh.entrant(1).orElseThrow().admission(); assertTrue(fresh.commit(current));
        assertTrue(fresh.start()); assertFalse(fresh.running(2)); assertTrue(fresh.running(1));
        assertTrue(fresh.eliminate(1)); assertFalse(fresh.eliminate(1)); assertFalse(fresh.active(1));
    }
    @Test void resultAndRewardAreOneTimeWithInventoryFullRetryAndNoSpectatorHostBotPrizes() {
        var session=session(2); assertTrue(session.reserve(999,false,100).isEmpty());
        assertTrue(session.reserve(1,true,100).isEmpty());
        var token=session.reserve(1,false,100).orElseThrow(); session.commit(token); session.closeEntry();
        assertTrue(session.reserve(2,false,100).isEmpty()); assertTrue(session.start());
        assertTrue(session.settle(Set.of(1,999,2))); assertFalse(session.settle(Set.of(1)));
        assertFalse(session.beginClaim(2)); assertFalse(session.beginClaim(999));
        assertTrue(session.beginClaim(1)); assertFalse(session.beginClaim(1));
        assertTrue(session.completeClaim(1,false)); assertTrue(session.beginClaim(1));
        assertTrue(session.completeClaim(1,true)); assertFalse(session.beginClaim(1));
        assertTrue(session.cancel().isEmpty());
        assertEquals(EventSession.Claim.GRANTED,session.entrant(1).orElseThrow().claim());
    }
    @Test void measuredProfileAndHumanReserveAreExplicitRatherThanCompanionCap() {
        var capacity=new EventSession.Capacity(400,500,50,10,"fixture-measured-profile",true);
        var session=new EventSession(2,EventDefinition.find("ox"),capacity,clock::get,0,1,999);
        session.open();
        for(int id=1;id<=390;id++) {
            var token=session.reserve(id,true,100).orElseThrow(); assertTrue(session.commit(token));
        }
        assertTrue(session.reserve(391,true,100).isEmpty());
        for(int id=500;id<510;id++) session.commit(session.reserve(id,false,100).orElseThrow());
        assertEquals(390,session.snapshot().bots()); assertEquals(10,session.snapshot().humans());
        assertThrows(IllegalArgumentException.class,()->new EventSession.Capacity(10,10,10,0,"",true));
        // This fixture demonstrates configurable admission math, NOT a measured runtime capacity.
    }
    @Test void courseCompletionSurvivesCancellationAndCanOnlyClaimOnce() {
        var session=new EventSession(1,EventDefinition.find("fitness"),EventSession.Capacity.development(2),clock::get,0,1,999);
        session.open(); session.commit(session.reserve(1,false,100).orElseThrow());
        session.commit(session.reserve(2,false,100).orElseThrow()); session.start();
        assertTrue(session.finishActor(1)); assertFalse(session.finishActor(1));
        assertEquals(1,session.cancel().size());
        assertTrue(session.beginClaim(1)); session.completeClaim(1,true); assertFalse(session.beginClaim(1));
        assertFalse(session.beginClaim(2));
    }
    @Test void courseDeadlineAndHostTransferCannotBeBypassed() {
        var session=session(2); session.commit(session.reserve(1,false,100).orElseThrow()); session.start();
        assertFalse(session.transferHost(999,1)); assertTrue(session.transferHost(999,900));
        assertFalse(session.transferHost(999,901)); assertEquals(900,session.hostId());
        clock.set(session.snapshot().deadlineMs()); assertFalse(session.finishActor(1));
    }
    @Test void spectatorsAndHostsConsumeVisibleCapacityButNotCompetitorSeats() {
        var session=new EventSession(1,EventDefinition.find("ox"),new EventSession.Capacity(2,4,4,0,"fixture",true),clock::get,0,1,999);
        session.open();session.hostVisible(true);
        assertTrue(session.commit(session.reserve(1,true,100,true).orElseThrow()));
        assertEquals(2,session.freeSeats());
        assertTrue(session.commit(session.reserve(2,false,100).orElseThrow()));
        assertTrue(session.commit(session.reserve(3,true,100).orElseThrow()));
        assertTrue(session.reserve(4,true,100,true).isEmpty());assertEquals(0,session.freeSeats());
        assertEquals(1,session.snapshot().spectators());session.start();assertFalse(session.active(1));assertFalse(session.finishActor(1));
    }
    @Test void explicitDevelopmentTrialDoesNotTurnCapacityIntoVerifiedEvidence() {
        var session=session(2);assertTrue(session.reserve(1,true,100).isEmpty());
        assertTrue(session.enableBotTrial());assertTrue(session.commit(session.reserve(1,true,100).orElseThrow()));
        assertFalse(session.snapshot().capacity().botsVerified());assertTrue(session.snapshot().capacity().measuredProfileId().startsWith("UNMEASURED"));
        session.start();assertFalse(session.enableBotTrial());
    }
}

package soloMapling.ArtificialPlayer.CompanionSystem;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService.*;

class SharedEventLeaseTest {
    private final AtomicLong clock=new AtomicLong();
    private final PriorActivity prior=new PriorActivity("TrainingBot",100,101);
    @Test void concurrentEventsShareAtomicWorldBudgetIncludingPendingSeats() throws Exception {
        var registry=new CompanionTaskService(clock::get,30); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<100;i++) { int id=i+100; results.add(pool.submit(()->{
                start.await();
                return registry.reserveEvent("event-"+id,id,0,1,EventRole.PARTICIPANT,1000,prior,5).isPresent();
            })); }
            start.countDown(); int accepted=0; for(var result:results) if(result.get()) accepted++;
            assertEquals(5,accepted);
            var first=registry.eventLeases().getFirst(); registry.commitEvent(first).orElseThrow();
            assertTrue(registry.reserveEvent("another",999,0,2,EventRole.HOST,1000,prior,5).isEmpty());
            assertTrue(registry.reserveEvent("different-world",999,1,1,EventRole.HOST,1000,prior,5).isPresent());
            clock.set(1000); // Four pending same-world seats expire; the committed actor remains counted.
            assertTrue(registry.reserveEvent("next",888,0,1,EventRole.PARTICIPANT,2000,prior,1).isEmpty());
            registry.releaseEvent(first.botId(),first.generation());
            assertTrue(registry.reserveEvent("next",888,0,1,EventRole.PARTICIPANT,2000,prior,1).isPresent());
        }
    }
    @Test void companionAndEventRaceOneBotWithOneExclusiveOwner() throws Exception {
        var registry=new CompanionTaskService(clock::get,30); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<100;i++) { int request=i; results.add(pool.submit(()->{
                start.await();
                return request%2==0 ? registry.reserve(request+1,20001,new PartyKey(0,request+1),1,1,1,15000,prior).isPresent()
                        : registry.reserveEvent("event-"+request,20001,0,1,EventRole.PARTICIPANT,15000,prior).isPresent();
            })); }
            start.countDown(); int owners=0; for(var result:results) if(result.get()) owners++;
            assertEquals(1,owners); assertTrue(registry.owned(20001));
        }
    }
    @Test void eventsDoNotConsumePartyCapAndReleasePriorActivityExactlyOnce() {
        var registry=new CompanionTaskService(clock::get,1);
        var party=registry.reserve(1,10,new PartyKey(0,1),1,1,1,15000,prior).orElseThrow();
        registry.commit(party,()->true).orElseThrow();
        for(int id=100;id<200;id++) {
            var pending=registry.reserveEvent("one-event",id,0,1,EventRole.PARTICIPANT,15000,prior).orElseThrow();
            var active=registry.commitEvent(pending).orElseThrow();
            assertTrue(registry.reserve(2,id,new PartyKey(0,2),2,1,1,15000,prior).isEmpty());
            assertEquals(prior,registry.releaseEvent(id,active.generation()).orElseThrow().prior());
            assertTrue(registry.releaseEvent(id,active.generation()).isEmpty());
        }
        assertEquals(1,registry.occupiedCapacity());
    }
    @Test void expiryAndOldGenerationCannotCommitOrReleaseNewEventLease() {
        var registry=new CompanionTaskService(clock::get,30);
        var old=registry.reserveEvent("old",10,0,1,EventRole.HOST,1000,prior).orElseThrow();
        clock.set(1000); assertTrue(registry.commitEvent(old).isEmpty()); assertFalse(registry.owned(10));
        var fresh=registry.reserveEvent("new",10,0,1,EventRole.HOST,2000,prior).orElseThrow();
        assertTrue(registry.releaseEvent(10,old.generation()).isEmpty());
        assertTrue(registry.commitEvent(fresh).isPresent());
        clock.set(10000); assertTrue(registry.owned(10));
        assertTrue(registry.commitEvent(old).isEmpty());
    }
}

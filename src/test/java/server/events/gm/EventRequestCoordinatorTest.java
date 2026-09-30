package server.events.gm;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class EventRequestCoordinatorTest {
    private static final EventRequestCoordinator.ChannelId CHANNEL=new EventRequestCoordinator.ChannelId(0,1);
    private static class Gateway implements EventRequestCoordinator.Gateway {
        boolean approved=true,busy,success=true;Set<Integer> offline=new HashSet<>();Set<Integer> accepted=null;List<EventRequestCoordinator.Request> dispatched=new ArrayList<>();
        public boolean approved(String key) {return approved && Set.of("ox","fitness").contains(key);}
        public boolean ready(int id,EventRequestCoordinator.ChannelId channel) {return !offline.contains(id);}
        public boolean busy(EventRequestCoordinator.ChannelId channel) {return busy;}
        public EventRequestCoordinator.Delivery dispatch(EventRequestCoordinator.Request request) {
            dispatched.add(request);
            return success?new EventRequestCoordinator.Delivery(accepted==null?request.accountActors().keySet():accepted):null;
        }
    }
    @Test void duplicateAccountsCoalesceAndCooldownsAreAccountScoped() {
        AtomicLong clock=new AtomicLong(1);Gateway gateway=new Gateway();var queue=new EventRequestCoordinator(clock::get,gateway);
        assertTrue(queue.request(10,100,CHANNEL,"ox",4).startsWith("Queued"));
        assertTrue(queue.request(10,101,CHANNEL,"fitness",4).contains("in flight"));
        assertTrue(queue.request(20,200,CHANNEL,"ox",4).contains("coalesced"));assertEquals(1,queue.queued());
        queue.pump();assertEquals(1,gateway.dispatched.size());assertEquals(Map.of(10,100,20,200),gateway.dispatched.getFirst().accountActors());
        assertTrue(queue.request(10,101,CHANNEL,"ox",4).contains("cooldown"));assertEquals(0,queue.queued());
        clock.addAndGet(30*60_000);assertTrue(queue.request(10,101,CHANNEL,"ox",4).startsWith("Queued"));
    }
    @Test void channelGapEmptyQueueAndFailedHostDoNotFabricateAcceptance() {
        AtomicLong clock=new AtomicLong(1);Gateway gateway=new Gateway();var queue=new EventRequestCoordinator(clock::get,gateway);
        queue.completed(CHANNEL);queue.request(10,100,CHANNEL,"ox",4);queue.pump();assertTrue(gateway.dispatched.isEmpty());
        clock.addAndGet(5*60_000);gateway.success=false;queue.pump();assertEquals(1,queue.queued());
        gateway.offline.add(100);queue.pump();assertEquals(0,queue.queued());
        gateway.offline.clear();assertTrue(queue.request(10,100,CHANNEL,"ox",4).startsWith("Queued"));
        clock.addAndGet(10*60_000);assertEquals(0,queue.queued());
        gateway.approved=false;assertTrue(queue.request(10,100,CHANNEL,"ox",4).contains("not passed"));
    }
    @Test void unknownKeysAndCancelledRequestsNeverReachGateway() {
        Gateway gateway=new Gateway();var queue=new EventRequestCoordinator(()->1,gateway);
        assertTrue(queue.request(10,100,CHANNEL,"8800000",4).contains("not passed"));
        queue.request(10,100,CHANNEL,"ox",4);queue.cancelChannel(CHANNEL);queue.pump();assertTrue(gateway.dispatched.isEmpty());
        assertTrue(queue.request(10,100,CHANNEL,"fitness",4).startsWith("Queued"));
    }
    @Test void dispatchExceptionReleasesStartingFenceAndBoundedCoalescingCanRetry() {
        Gateway gateway=new Gateway() {
            boolean first=true;
            public EventRequestCoordinator.Delivery dispatch(EventRequestCoordinator.Request request) {
                if(first) {first=false;throw new IllegalStateException("host preparation failed");}
                return super.dispatch(request);
            }
        };
        var queue=new EventRequestCoordinator(()->1,gateway);
        queue.request(10,100,CHANNEL,"ox",2);assertThrows(IllegalStateException.class,queue::pump);
        assertTrue(queue.request(20,200,CHANNEL,"ox",2).contains("coalesced"));
        assertTrue(queue.request(30,300,CHANNEL,"ox",2).contains("full"));
        queue.pump();assertEquals(0,queue.queued());assertEquals(2,gateway.dispatched.getFirst().accountActors().size());
    }
    @Test void emptyOrPartialArrivalOnlyChargesAccountsThatActuallyEntered() {
        AtomicLong clock=new AtomicLong(1);Gateway gateway=new Gateway();var queue=new EventRequestCoordinator(clock::get,gateway);
        queue.request(10,100,CHANNEL,"ox",4);
        queue.request(20,200,CHANNEL,"ox",4);
        gateway.accepted=Set.of(20);
        queue.pump();
        assertEquals(0,queue.queued());
        assertTrue(queue.request(10,100,CHANNEL,"ox",4).startsWith("Queued"));
        assertTrue(queue.request(20,200,CHANNEL,"ox",4).contains("cooldown"));
        queue.cancelChannel(CHANNEL);
        gateway.accepted=Set.of();
        queue.request(30,300,CHANNEL,"ox",4);
        queue.pump();
        assertEquals(0,queue.queued());
        assertTrue(queue.request(30,300,CHANNEL,"ox",4).startsWith("Queued"));
    }
}

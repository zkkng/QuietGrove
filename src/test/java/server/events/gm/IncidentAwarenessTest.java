package server.events.gm;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IncidentAwarenessTest {
    private IncidentAwareness model() {
        return new IncidentAwareness(7,List.of(new IncidentAwareness.Candidate(1,100,List.of(2),.7,2000),
                new IncidentAwareness.Candidate(2,200,List.of(3),.7,2000),new IncidentAwareness.Candidate(3,300,List.of(),.5,5000),
                new IncidentAwareness.Candidate(4,400,List.of(),.9,6000)));
    }
    @Test void silentAwarenessRequiresLocalWitnessAndDelayedValidReport() {
        var incident=model();assertTrue(incident.pump(100_000,7).isEmpty());
        assertTrue(incident.witness(1,100,1000));assertFalse(incident.witness(99,100,1000));
        incident.report(1,List.of(),1000);incident.pump(1001,7);
        assertEquals(IncidentAwareness.State.UNAWARE,incident.response(2).state());
        incident.pump(20_000,7);assertEquals(IncidentAwareness.State.WITNESS,incident.response(2).state());
        assertEquals(1,incident.response(2).evidence().sourceId());assertTrue(incident.response(2).evidence().confidence()<1);
        assertEquals(IncidentAwareness.State.UNAWARE,incident.response(3).state());assertEquals(IncidentAwareness.State.UNAWARE,incident.response(4).state());
    }
    @Test void ownershipRoutesAndAbilityLimitActualFiniteDeparture() {
        var incident=model();incident.announce(List.of(1,2,3),100,1000);incident.pump(30_000,7);
        assertEquals(IncidentAwareness.State.FLEE,incident.decide(1,false,true,1,10,30_000));
        assertEquals(IncidentAwareness.State.FLEE,incident.decide(2,true,false,1,10,30_000));
        assertEquals(IncidentAwareness.State.PREPARING,incident.decide(3,true,true,.7,2,30_000));
        assertFalse(incident.depart(3,30_001));assertTrue(incident.depart(3,40_000));assertFalse(incident.depart(3,40_000));
        assertTrue(incident.arrive(3,60_000));assertEquals(4,incident.population());
        incident.casualty(3,70_000);incident.recovered(3,71_000);assertEquals(IncidentAwareness.State.DEAD,incident.response(3).state());
        incident.recovered(3,77_000);assertEquals(IncidentAwareness.State.RECOVERING,incident.response(3).state());
        assertTrue(incident.response(3).due()>=137_000);assertFalse(incident.depart(3,200_000));
    }
    @Test void oldGenerationAndCancellationCannotDeliverReportsOrNewArrivals() {
        var incident=model();incident.witness(1,100,1000);incident.report(1,List.of(2,3,4),1000);
        assertTrue(incident.pump(20_000,6).isEmpty());assertEquals(IncidentAwareness.State.UNAWARE,incident.response(2).state());
        incident.cancel();assertTrue(incident.pump(20_000,7).isEmpty());assertFalse(incident.depart(1,20_000));
        assertTrue(incident.responses().stream().allMatch(r->r.state()==IncidentAwareness.State.RELEASED));
    }
}

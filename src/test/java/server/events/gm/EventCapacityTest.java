package server.events.gm;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EventCapacityTest {
    private EventCapacityProfiles.Profile profile(boolean verified,int soak,int clients) {
        var evidence=new EventCapacityProfiles.Measurements(3,soak,clients,1000,1200,70,150,100,65,65,45,100,false,0,true,"Baseline legacy client test machine","docs/event-capacity-results/example-evidence.csv");
        return new EventCapacityProfiles.Profile("test-evidence","ox",1,List.of(109020001),1000,1000,1200,1000,1500,100,20,10,10,.15,verified,verified,true,evidence);
    }
    @Test void guessedOrNetworkOnlyOrShortRunsCannotApproveCapacity() {
        assertFalse(profile(false,60,1).verified(EventDefinition.find("ox")));
        assertFalse(profile(true,59,1).verified(EventDefinition.find("ox")));
        assertFalse(profile(true,60,0).verified(EventDefinition.find("ox")));
        assertFalse(profile(true,60,1).verified(EventDefinition.find("fitness")));
    }
    @Test void measuredProfileKeepsActiveVisibleHumanReserveAndHeadroomDistinct() {
        var profile=profile(true,60,1);assertTrue(profile.verified(EventDefinition.find("ox")));
        assertEquals(850,profile.capacity().activeLimit());assertEquals(1020,profile.capacity().visibleLimit());assertEquals(20,profile.capacity().humanReserve());
        var session=new EventSession(1,EventDefinition.find("ox"),profile.capacity(),()->1,0,1,99999);session.open();
        for(int id=1;id<=830;id++) assertTrue(session.commit(session.reserve(id,true,100).orElseThrow()));
        assertTrue(session.reserve(831,true,100).isEmpty());
    }
    @Test void playerRequestsRequireAVisibleHostSlotAndReservedHumanSeat() {
        var measured=profile(true,60,1);
        var noHumanReserve=new EventCapacityProfiles.Profile(measured.id(),measured.eventKey(),measured.definitionVersion(),
                measured.maps(),measured.serverActive(),measured.clientActive(),measured.visible(),
                measured.operatorActive(),measured.worldLimit(),measured.pending(),0,
                measured.arrivalBurst(),measured.promotionBudget(),measured.headroom(),true,true,true,measured.measurements());
        assertFalse(noHumanReserve.verified(EventDefinition.find("ox")));
        var noHostSpace=new EventCapacityProfiles.Profile(measured.id(),measured.eventKey(),measured.definitionVersion(),
                measured.maps(),1,1,1,1,1,measured.pending(),1,
                measured.arrivalBurst(),measured.promotionBudget(),measured.headroom(),true,true,true,measured.measurements());
        assertFalse(noHostSpace.verified(EventDefinition.find("ox")));
    }
    @Test void sustainedLoadShedsOptionalWorkThenRecoversSlowlyWithoutChangingScores() {
        var governor=new EventGovernor();
        assertEquals(EventGovernor.Mode.CLOSE_ADMISSION,governor.update(new EventGovernor.Sample(0,210,0,50,50,false)));
        assertEquals(EventGovernor.Mode.SLOW_ARRIVALS,governor.update(new EventGovernor.Sample(2100,210,0,50,50,false)));
        assertEquals(EventGovernor.Mode.QUIET_SPECTATORS,governor.update(new EventGovernor.Sample(5100,210,0,50,50,false)));
        governor.update(new EventGovernor.Sample(5200,10,0,50,50,false));
        assertEquals(EventGovernor.Mode.QUIET_SPECTATORS,governor.update(new EventGovernor.Sample(20000,10,0,50,50,false)));
        assertEquals(EventGovernor.Mode.SLOW_ARRIVALS,governor.update(new EventGovernor.Sample(36000,10,0,50,50,false)));
        assertFalse(governor.admits());assertFalse(governor.arrivals());
        governor.update(new EventGovernor.Sample(50000,1200,3000,90,95,true));
        assertEquals(EventGovernor.Mode.STOP,governor.update(new EventGovernor.Sample(60000,1200,3000,90,95,true)));
        assertEquals(EventGovernor.Mode.STOP,governor.update(new EventGovernor.Sample(60100,210,0,50,50,false)));
        assertEquals(EventGovernor.Mode.STOP,governor.update(new EventGovernor.Sample(100000,0,0,50,50,false)));
    }
}

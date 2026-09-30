package server.events.gm;

import constants.id.MapId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CourseEventTest {
    @Test void fitnessCannotSkipStagesOrFinishThroughWrongPortal() {
        var course=new CourseEvent(EventDefinition.find("fitness"),17);
        course.admit(1);
        assertNull(course.plan(1,109040004,"in00",MapId.EVENT_WINNER,"start00"));
        assertNull(course.plan(1,109040000,"in00",109040002,"start00"));
        for(int source=109040000;source<=109040004;source++) {
            int target=source==109040004?MapId.EVENT_WINNER:source+1;
            var transition=course.plan(1,source,"in00",target,"start00");
            assertNotNull(transition);
            assertNull(course.plan(1,source,"in00",target,"start00"));
            assertTrue(course.commit(transition,target));
        }
        assertEquals(java.util.Set.of(1),course.finished());
        assertNull(course.plan(1,109040004,"in00",MapId.EVENT_WINNER,"start00"));
    }
    @Test void olaUsesSameDoorsForEveryEntrantAndWrongDoorReturnsToActualStage() {
        var course=new CourseEvent(EventDefinition.find("ola"),73);
        course.admit(1); course.admit(2);
        for(int source=109030001;source<=109030003;source++) {
            int count=source==109030001?5:source==109030002?8:16;
            int good=0,winningDoor=-1;
            for(int index=0;index<count;index++) {
                String door=String.format("ch%02d",index);
                var first=course.plan(1,source,door,MapId.NONE,"");
                var second=course.plan(2,source,door,MapId.NONE,"");
                assertNotNull(first); assertEquals(first.destinationMap(),second.destinationMap());
                if(first.destinationMap()!=source) {good++; winningDoor=index;}
                course.abandon(first); course.abandon(second);
            }
            assertEquals(source==109030002?3:2,good);
            for(int actor=1;actor<=2;actor++) {
                var transition=course.plan(actor,source,String.format("ch%02d",winningDoor),MapId.NONE,"");
                assertTrue(course.commit(transition,transition.destinationMap()));
            }
        }
        assertEquals(java.util.Set.of(1,2),course.finished());
    }
    @Test void failedWarpDoesNotAdvanceAndRemovedEntrantsCannotUseDoors() {
        var course=new CourseEvent(EventDefinition.find("fitness"),1); course.admit(1);
        var transition=course.plan(1,109040000,"in00",109040001,"start00");
        assertFalse(course.commit(transition,109040000)); assertTrue(course.atStage(1,109040000));
        course.remove(1); assertNull(course.plan(1,109040000,"in00",109040001,"start00"));
    }
}

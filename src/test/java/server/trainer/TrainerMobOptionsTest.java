package server.trainer;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Set;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import scripting.event.EventInstanceManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainerMobOptionsTest {
    @Test void rangeAndExclusionWinOverIncludedIds() {
        var option = new TrainerMobOptions(true,true,true,100,Set.of(100,101),Set.of(101),false,0,0,17,0);
        assertTrue(option.accepts(100,new Point(),new Point(100,0)));
        assertFalse(option.accepts(100,new Point(),new Point(101,0)));
        assertFalse(option.accepts(101,new Point(),new Point()));
        assertFalse(option.accepts(102,new Point(),new Point()));
    }
    @Test void protectedMonstersAlwaysRetainOrdinaryBehavior() {
        Monster mob = mock(Monster.class); MonsterStats stats = mock(MonsterStats.class); MapleMap map = mock(MapleMap.class);
        when(mob.isAlive()).thenReturn(true); when(mob.getStats()).thenReturn(stats); when(mob.getMap()).thenReturn(map);
        assertTrue(TrainerMobOptions.ordinary(mob));
        when(mob.isBoss()).thenReturn(true); assertFalse(TrainerMobOptions.ordinary(mob));
        when(mob.isBoss()).thenReturn(false); when(mob.getIncidentOwner()).thenReturn("incident"); assertFalse(TrainerMobOptions.ordinary(mob));
        when(mob.getIncidentOwner()).thenReturn(null); when(map.getEventInstance()).thenReturn(mock(EventInstanceManager.class)); assertFalse(TrainerMobOptions.ordinary(mob));
    }
    @Test void pointSpreadClampsToMapAndApproachStepIsBounded() {
        var point = new TrainerMobOptions(false,false,false,0,Set.of(),Set.of(),true,190,100,100,0);
        Rectangle map = new Rectangle(-200,-200,400,400);
        assertEquals(new Point(199,80),point.destination(new Point(),new Point(),1,4,map,"front"));
        var smooth = new TrainerMobOptions(false,false,false,0,Set.of(),Set.of(),true,190,100,100,25);
        Point target = smooth.destination(new Point(),new Point(),1,4,map,"front");
        assertTrue(target.distanceSq(new Point()) <= 625); assertTrue(target.x > 0);
    }
    @Test void invalidCoordinatesAndWorkBudgetsRejectBeforeChanges() {
        assertThrows(IllegalArgumentException.class, () -> new TrainerMobOptions(true,false,false,3001,Set.of(),Set.of(),false,0,0,17,0));
        assertThrows(IllegalArgumentException.class, () -> new TrainerMobOptions(true,false,false,0,Set.of(),Set.of(),true,40000,0,17,0));
        assertThrows(IllegalArgumentException.class, () -> new TrainerMobOptions(true,false,false,0,Set.of(),Set.of(),true,0,0,17,501));
        assertFalse(TrainerMobOptions.OFF.freeze()); assertFalse(TrainerMobOptions.OFF.disarm()); assertFalse(TrainerMobOptions.OFF.aggro());
    }
}

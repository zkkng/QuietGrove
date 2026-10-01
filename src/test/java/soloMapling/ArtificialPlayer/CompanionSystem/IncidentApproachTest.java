package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import java.awt.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncidentApproachTest {
    @Test void arrivingFighterChoosesNearestOwnedLivingSpawn() {
        var bot=mock(Character.class);var map=mock(MapleMap.class);
        var distant=mock(Monster.class);var nearest=mock(Monster.class);var unrelated=mock(Monster.class);
        when(bot.getMap()).thenReturn(map);when(bot.getPosition()).thenReturn(new Point(0,0));
        when(map.getAllMonsters()).thenReturn(List.of(distant,unrelated,nearest));
        when(distant.isAlive()).thenReturn(true);when(distant.getPosition()).thenReturn(new Point(900,0));
        when(unrelated.isAlive()).thenReturn(true);when(unrelated.getPosition()).thenReturn(new Point(20,0));
        when(nearest.isAlive()).thenReturn(true);when(nearest.getPosition()).thenReturn(new Point(300,0));
        when(map.getPointBelow(new Point(300,0))).thenReturn(new Point(300,20));
        when(map.getMapArea()).thenReturn(new Rectangle(-100,-100,1500,500));
        try(var runtime=mockStatic(CompanionRuntime.class);var controller=mockStatic(BossMonsterController.class);
            var geometry=mockStatic(BossGeometry.class);var movement=mockStatic(GCMovement.class)) {
            runtime.when(()->CompanionRuntime.combatAllowed(bot,7)).thenReturn(true);
            controller.when(()->BossMonsterController.targetAllowed(bot,distant)).thenReturn(true);
            controller.when(()->BossMonsterController.targetAllowed(bot,nearest)).thenReturn(true);
            geometry.when(()->BossGeometry.aim(nearest)).thenReturn(new Point(300,0));
            assertTrue(CompanionCombat.approachIncident(bot,7));
            movement.verify(()->GCMovement.move(bot,300,19),times(1));
        }
    }

    @Test void distantFighterWalksOnlyToOwnedIncidentThreatOnARealFoothold() {
        var bot=mock(Character.class);var map=mock(MapleMap.class);var threat=mock(Monster.class);
        when(bot.getMap()).thenReturn(map);when(bot.getPosition()).thenReturn(new Point(0,0));
        when(map.getAllMonsters()).thenReturn(List.of(threat));when(threat.isAlive()).thenReturn(true);
        when(threat.getPosition()).thenReturn(new Point(1500,300));
        when(map.getPointBelow(any())).thenReturn(new Point(1500,334));
        when(map.getMapArea()).thenReturn(new Rectangle(-1000,-1000,8000,3000));
        try(var runtime=mockStatic(CompanionRuntime.class);var controller=mockStatic(BossMonsterController.class);
            var geometry=mockStatic(BossGeometry.class);var movement=mockStatic(GCMovement.class)) {
            runtime.when(()->CompanionRuntime.combatAllowed(bot,11)).thenReturn(true);
            controller.when(()->BossMonsterController.targetAllowed(bot,threat)).thenReturn(true);
            geometry.when(()->BossGeometry.aim(threat)).thenReturn(new Point(1500,300));
            assertTrue(CompanionCombat.approachIncident(bot,11));
            movement.verify(()->GCMovement.move(bot,1500,333),times(1));
            assertFalse(CompanionCombat.approachIncident(bot,12));
            controller.when(()->BossMonsterController.targetAllowed(bot,threat)).thenReturn(false);
            assertFalse(CompanionCombat.approachIncident(bot,11));
            controller.when(()->BossMonsterController.targetAllowed(bot,threat)).thenReturn(true);
            when(map.getPointBelow(any())).thenReturn(null);assertFalse(CompanionCombat.approachIncident(bot,11));
            movement.verify(()->GCMovement.move(bot,1500,333),times(1));
            verify(bot,never()).changeMap(any(MapleMap.class),any(server.maps.Portal.class));
        }
    }
}

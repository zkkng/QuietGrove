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

package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BuffStat;
import client.Character;
import org.junit.jupiter.api.Test;
import server.events.gm.EventBotRuntime;
import server.events.gm.EventMapObstacles;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionIncomingDamage;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionRuntime;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotEventObstaclesTest {
    @Test void trapCostsActualHpAndMagicGuardMpAndRejectsDuplicateAfterSharedHit() {
        Character bot = mock(Character.class); MapleMap map = mock(MapleMap.class);
        when(bot.isAlive()).thenReturn(true); when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(109080000); when(bot.getPosition()).thenReturn(new Point(45, -202));
        when(bot.getMp()).thenReturn(10); when(bot.getBuffedValue(BuffStat.MAGIC_GUARD)).thenReturn(50);
        when(bot.getBuffedValue(BuffStat.STANCE)).thenReturn(100);
        var state = new BotMovementState(bot, null);
        try (var events = mockStatic(EventBotRuntime.class); var runtime = mockStatic(CompanionRuntime.class);
             var geometry = mockStatic(EventMapObstacles.class); var effects = mockStatic(BotContactDamage.class)) {
            events.when(() -> EventBotRuntime.physical(bot)).thenReturn(true);
            runtime.when(() -> CompanionRuntime.active(bot)).thenReturn(true);
            geometry.when(() -> EventMapObstacles.collision(eq(map), any(Point.class), any(Point.class), anyLong()))
                    .thenReturn(new EventMapObstacles.Collision(20, 45));
            BotEventObstacles.tick(state, bot);
            verify(bot).addHP(-10); verify(bot).addMP(-10);
            assertTrue(state.mobHitCooldownMs > 0);
            state.mobHitCooldownMs = 0; // Another damage source still owns the shared i-frame window.
            BotEventObstacles.tick(state, bot);
            verify(bot, times(1)).addHP(anyInt());
            verify(map, times(1)).broadcastMessage(eq(bot), any(), eq(false));
        } finally { CompanionIncomingDamage.clear(bot); }
    }
    @Test void nonparticipantAndDeadActorHaveNoTrapEffects() {
        Character bot = mock(Character.class); var state = new BotMovementState(bot, null);
        try (var events = mockStatic(EventBotRuntime.class); var geometry = mockStatic(EventMapObstacles.class)) {
            BotEventObstacles.tick(state, bot);
            events.when(() -> EventBotRuntime.physical(bot)).thenReturn(true);
            BotEventObstacles.tick(state, bot);
            geometry.verifyNoInteractions(); verify(bot, never()).addHP(anyInt());
        }
    }
}

package soloMapling.ArtificialPlayer.CompanionSystem;

import client.BuffStat;
import client.Character;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionIncomingDamageTest {
    @Test void guardConsumesRealMpAndSharedIframeRejectsDuplicate() {
        Character bot = mock(Character.class);
        when(bot.isAlive()).thenReturn(true);
        when(bot.getMp()).thenReturn(40);
        when(bot.getBuffedValue(BuffStat.MAGIC_GUARD)).thenReturn(80);
        try (var runtime = mockStatic(CompanionRuntime.class)) {
            runtime.when(() -> CompanionRuntime.active(bot)).thenReturn(true);
            assertEquals(60, CompanionIncomingDamage.apply(bot, 100, false));
            verify(bot).addMP(-40); verify(bot).addHP(-60);
            assertEquals(-1, CompanionIncomingDamage.apply(bot, 100, false));
            verify(bot, times(1)).addHP(anyInt());
        }
    }
    @Test void fallCannotKillAndInactiveAmbientBotsAreNotDamaged() {
        Character bot = mock(Character.class);
        when(bot.isAlive()).thenReturn(true);
        when(bot.getHp()).thenReturn(8);
        try (var runtime = mockStatic(CompanionRuntime.class)) {
            assertEquals(-1, CompanionIncomingDamage.apply(bot, 100, false));
            runtime.when(() -> CompanionRuntime.active(bot)).thenReturn(true);
            assertEquals(7, CompanionIncomingDamage.apply(bot, 100, true));
            verify(bot).addHP(-7); verify(bot, never()).addMP(anyInt());
        }
    }
}

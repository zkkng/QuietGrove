package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import client.Character;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionRuntime;
import java.awt.Point;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossContactDamageTest {
    @Test void activeMultipartContactQueryIncludesAnOffsetHeadsGroundAnchor() {
        var bot=mock(Character.class);when(bot.getPosition()).thenReturn(new Point(-170,-400));
        try(var runtime=mockStatic(CompanionRuntime.class)) {
            runtime.when(()->CompanionRuntime.active(bot)).thenReturn(false);
            assertFalse(BotContactDamage.contactQuery(null,bot).contains(new Point(0,0)));
            runtime.when(()->CompanionRuntime.active(bot)).thenReturn(true);
            assertTrue(BotContactDamage.contactQuery(null,bot).contains(new Point(0,0)));
            assertTrue(BotMobHitboxProvider.getMobBounds(8810002,new Point(),false)
                    .intersects(new java.awt.Rectangle(-170,-450,1,51)));
        }
    }
    @Test void nonContactWzActorsAndCanonicalEncounterMarkersCannotInflictTouchDamage() {
        var stats=new MonsterStats();stats.setHp(100);stats.bodyAttack=false;
        assertFalse(stats.copy().bodyAttack);
        assertFalse(BotContactDamage.isHostileLivingMonster(new Monster(2220000,stats)));
        stats.bodyAttack=true;
        assertTrue(BotContactDamage.isHostileLivingMonster(new Monster(2220000,stats)));
        assertFalse(BotContactDamage.isHostileLivingMonster(new Monster(8810010,stats)));
        assertFalse(BotContactDamage.isHostileLivingMonster(new Monster(8810018,stats)));
        var fake=new Monster(2220000,stats);fake.setFake(true);
        assertFalse(BotContactDamage.isHostileLivingMonster(fake));
    }
}

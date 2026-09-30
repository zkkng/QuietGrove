package server.trainer;

import client.Character;
import server.maps.MapleMap;
import java.awt.Point;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainerSocialStockTest {
    @Test void stableNamesAreUniqueAcrossTownChannelAndSlot() {
        var names = new HashSet<String>();
        for (int map : new int[]{100000000, 105040401, 100000102})
            for (int channel = 1; channel <= 20; channel++) for (int slot = 0; slot < 200; slot++) {
                String name = TrainerSocialStock.name(map, channel, slot);
                assertTrue(names.add(name)); assertTrue(name.matches("[A-Za-z0-9]{4,13}"));
                assertTrue(TrainerSocialStock.eligible(name, map, channel));
                assertFalse(TrainerSocialStock.eligible(name, map + 1, channel));
            }
    }
    @Test void genericOrMisroutedBotsCannotObtainCampaignStock() {
        assertFalse(TrainerSocialStock.eligible("RandomBot", 100000000, 1));
        assertFalse(TrainerSocialStock.eligible(TrainerSocialStock.name(100000000, 1, 0), 100000000, 2));
        assertThrows(IllegalArgumentException.class, () -> TrainerSocialStock.name(100000000, 1, 1296));
    }
    @Test void invitationMustBeAffirmative() {
        assertTrue(SocialStakeGame.invitation("Let's play a drop game!"));
        assertTrue(SocialStakeGame.invitation("show yours and I'll show mine"));
        assertFalse(SocialStakeGame.invitation("Don't play a drop game"));
        assertFalse(SocialStakeGame.invitation("no drop game"));
    }
    @Test void lossWitnessMustSeeBothDropAndVisibleActorInSameMap() {
        Character observer = mock(Character.class), actor = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(observer.getMap()).thenReturn(map); when(actor.getMap()).thenReturn(map);
        when(observer.getPosition()).thenReturn(new Point(0, 0)); when(actor.getPosition()).thenReturn(new Point(800, 0));
        assertTrue(TrainerVenuePickup.witnessed(observer, actor, new Point(20, 0)));
        when(actor.isHidden()).thenReturn(true);
        assertFalse(TrainerVenuePickup.witnessed(observer, actor, new Point(20, 0)));
        when(actor.isHidden()).thenReturn(false); when(actor.getPosition()).thenReturn(new Point(901, 0));
        assertFalse(TrainerVenuePickup.witnessed(observer, actor, new Point(20, 0)));
        when(actor.getPosition()).thenReturn(new Point(20, 0)); when(actor.getMap()).thenReturn(mock(MapleMap.class));
        assertFalse(TrainerVenuePickup.witnessed(observer, actor, new Point(20, 0)));
    }
}

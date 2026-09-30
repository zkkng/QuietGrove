package server.trainer;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import java.awt.Point;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainerLootAdvancedTest {
    private MapItem drop(int id) {
        var owner=mock(Character.class); when(owner.getId()).thenReturn(123); when(owner.getPartyId()).thenReturn(-1);
        return new MapItem(new Item(id,(short)0,(short)1),new Point(0,0),owner,owner,null,(byte)2,true);
    }
    @Test void venueAndCategoryFiltersUseActualTaggedDropIdentity() {
        var options=new TrainerLootAdvanced(false,false,false,0,false,50,"","scroll",0,"venue",25,25);
        var scroll=drop(2040811); assertFalse(options.accepts(scroll)); scroll.markVenueAsset(UUID.randomUUID(),UUID.randomUUID()); assertTrue(options.accepts(scroll));
        var potion=drop(2000002); potion.markVenueAsset(UUID.randomUUID(),UUID.randomUUID()); assertFalse(options.accepts(potion));
    }
    @Test void resetClearsEveryAutomationModeWhileRetainingFilters() {
        var active=new TrainerLootAdvanced(true,true,true,2,true,50,"glove","scroll",1,"venue",4,2);
        var paused=active.paused(); assertFalse(paused.automated()); assertFalse(paused.feeder()); assertEquals("glove",paused.name()); assertEquals(2,paused.petIndex());
    }
    @Test void invalidPetQuotaAndUnknownSourceRejectBeforePickup() {
        assertThrows(IllegalArgumentException.class,()->new TrainerLootAdvanced(false,true,false,3,false,50,"","all",0,"all",25,25));
        assertThrows(IllegalArgumentException.class,()->new TrainerLootAdvanced(false,true,false,0,false,50,"","all",0,"all",26,25));
        assertThrows(IllegalArgumentException.class,()->new TrainerLootAdvanced(false,true,false,0,false,50,"","all",0,"trade escrow",25,25));
    }
}

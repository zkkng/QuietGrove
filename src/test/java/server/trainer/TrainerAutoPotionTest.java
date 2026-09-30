package server.trainer;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.maps.MapleMap;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainerAutoPotionTest {
    @Test void boundedCadenceConsumesRealUnitsPreservesReserveAndDoesNotRefillOnReconfigure() {
        Character actor = mock(Character.class); Client client = mock(Client.class);
        when(actor.getClient()).thenReturn(client); when(client.getPlayer()).thenReturn(actor);
        when(actor.isAlive()).thenReturn(true); when(actor.isLoggedinWorld()).thenReturn(true);
        when(actor.getMap()).thenReturn(mock(MapleMap.class));
        when(actor.getHp()).thenReturn(40); when(actor.getCurrentMaxHp()).thenReturn(100);
        when(actor.getMp()).thenReturn(20); when(actor.getCurrentMaxMp()).thenReturn(100);
        Inventory inventory = new Inventory(actor, InventoryType.USE, (byte) 24);
        when(actor.getInventory(InventoryType.USE)).thenReturn(inventory);
        inventory.addItemFromDB(new Item(2000002, (short) 1, (short) 7));
        inventory.addItemFromDB(new Item(2000006, (short) 2, (short) 7));
        var options = new TrainerAutoPotion.Options(true, true, 50, 30, 2000002, 2000006, 5, 1000);
        var effect = mock(StatEffect.class);
        var auto = new TrainerAutoPotion(id -> effect); auto.configure(options);
        when(effect.getHp()).thenReturn((short) 300); when(effect.getMp()).thenReturn((short) 300);
        when(effect.applyTo(actor)).thenReturn(true);
        {
            auto.tick(actor, 0);
            assertEquals(6, inventory.countById(2000002)); assertEquals(7, inventory.countById(2000006));
            auto.configure(options); auto.tick(actor, 999);
            assertEquals(6, inventory.countById(2000002));
            auto.tick(actor, 1000); auto.tick(actor, 2000);
            assertEquals(5, inventory.countById(2000002));
            var state = new HashMap<String,String>(); auto.status(state);
            assertEquals("0", state.get("autoHp")); assertEquals("1", state.get("autoMp"));
            auto.tick(actor, 3000); assertEquals(6, inventory.countById(2000006));
            verify(effect, times(3)).applyTo(actor);
            auto.off(); auto.tick(actor, 30_000); assertEquals(6, inventory.countById(2000006));
        }
    }

    @Test void deathTransitionAndDialogueCannotConsumeInventory() {
        var auto = new TrainerAutoPotion();
        auto.configure(new TrainerAutoPotion.Options(true, true, 50, 30, 2000002, 2000006, 0, 500));
        Character actor = mock(Character.class); Client client = mock(Client.class);
        when(actor.getClient()).thenReturn(client);
        auto.tick(actor, 0); // Dead.
        when(actor.isAlive()).thenReturn(true); when(actor.isLoggedinWorld()).thenReturn(true);
        when(client.isInTransition()).thenReturn(true); auto.tick(actor, 1000);
        when(client.isInTransition()).thenReturn(false);
        when(client.getCM()).thenReturn(mock(scripting.npc.NPCConversationManager.class)); auto.tick(actor, 2000);
        verify(actor, never()).getInventory(any());
    }

    @Test void thresholdAndConfigurationBoundsRejectInvalidRequestsBeforeAnyAction() {
        assertTrue(TrainerAutoPotion.below(50, 100, 50));
        assertFalse(TrainerAutoPotion.below(51, 100, 50));
        assertFalse(TrainerAutoPotion.below(10, 0, 50));
        assertFalse(TrainerAutoPotion.below(Integer.MAX_VALUE, Integer.MAX_VALUE, 99));
        assertThrows(IllegalArgumentException.class, () -> new TrainerAutoPotion.Options(true, false, 100, 30, 2000002, 2000006, 0, 500));
        assertThrows(IllegalArgumentException.class, () -> new TrainerAutoPotion.Options(true, false, 50, 30, 2340000, 2000006, 0, 500));
        assertThrows(IllegalArgumentException.class, () -> new TrainerAutoPotion.Options(true, false, 50, 30, 2000002, 2000006, 0, 499));
    }
}

package server.trainer;

import client.Character;
import client.Client;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import tools.DatabaseConnection;

import java.awt.Point;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainerVenuePickupTest {
    @Test void unresolvedCommitDisconnectsAndSuppressesOnlyThatCharacterInstanceSave() throws Exception {
        Character stale = mock(Character.class);
        Client client = mock(Client.class);
        when(stale.getClient()).thenReturn(client);
        doCallRealMethod().when(stale).saveCharToDB(true);
        TrainerVenuePickup.quarantine(stale);
        verify(client).disconnectSession();
        assertTrue(TrainerVenuePickup.hasUnresolved(stale));
        try (var database = mockStatic(DatabaseConnection.class)) {
            stale.saveCharToDB(true);
            database.verifyNoInteractions();
        }
        Character reloaded = mock(Character.class);
        assertFalse(TrainerVenuePickup.hasUnresolved(reloaded));
    }

    @Test void expiryIsInclusiveAndCleanupIsNotAClaim() {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(123);
        when(owner.getPartyId()).thenReturn(-1);
        MapItem drop = new MapItem(new Item(2049100, (short) 0, (short) 1),
                new Point(0, 0), owner, owner, null, (byte) 2, true);
        drop.markVenueAsset(UUID.randomUUID(), UUID.randomUUID());
        drop.setPickupExpiresAt(1_150);
        assertFalse(drop.pickupExpired(1_149));
        assertTrue(drop.pickupExpired(1_150));
        drop.setPickedUp(true);
        assertEquals(0, drop.getCollectedByCharacterId());
        assertThrows(IllegalArgumentException.class, () -> drop.markVenueAsset(UUID.randomUUID(), UUID.randomUUID()));
    }
}

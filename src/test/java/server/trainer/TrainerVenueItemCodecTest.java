package server.trainer;

import client.inventory.Item;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TrainerVenueItemCodecTest {
    @Test void ordinaryAssetSurvivesSnapshotWithoutCreatingMoreUnits() {
        Item scroll = new Item(2049100, (short) 3, (short) 1);
        scroll.setOwner("VenueHost");
        scroll.setSN(17);
        byte[] snapshot = TrainerVenueItemCodec.encode(scroll);
        Item recovered = TrainerVenueItemCodec.decode(snapshot);
        assertEquals(scroll.getItemId(), recovered.getItemId());
        assertEquals(1, recovered.getQuantity());
        assertEquals("VenueHost", recovered.getOwner());
        assertEquals(17, recovered.getSN());
        assertNotSame(scroll, recovered);
    }

    @Test void rejectsPetStacksAndCorruptSnapshots() {
        assertThrows(IllegalArgumentException.class,
                () -> TrainerVenueItemCodec.encode(new Item(2049100, (short) 1, (short) 2)));
        byte[] valid = TrainerVenueItemCodec.encode(new Item(2049100, (short) 1, (short) 1));
        byte[] corrupted = Arrays.copyOf(valid, valid.length);
        corrupted[0] ^= 0x40;
        assertThrows(IllegalArgumentException.class, () -> TrainerVenueItemCodec.decode(corrupted));
        assertThrows(IllegalArgumentException.class, () -> TrainerVenueItemCodec.decode(new byte[2049]));
    }
}

package server.trainer;

import client.Character;
import client.inventory.Item;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainerVenueLifecycleTest {
    @Test void preplayShutdownReturnsEscrowAndDefersDisconnectedRefundExactlyOnce() throws Exception {
        var table = new TrainerVenueService.Table(mock(MapleMap.class));
        table.round = UUID.randomUUID(); table.phase = "ESCROW"; table.player = mock(Character.class);
        table.pending = new TrainerVenueLedger.Asset(UUID.randomUUID(), 105040401, 1, "VenueHost",
                new Item(2040811, (short) 0, (short) 1), 1_600_000);
        UUID asset = table.pending.id(), round = table.round;
        try (var ledger = mockStatic(TrainerVenueLedger.class)) {
            ledger.when(() -> TrainerVenueLedger.returnEscrow(round, asset)).thenReturn(true);
            table.stop(); table.stop();
            ledger.verify(() -> TrainerVenueLedger.returnEscrow(round, asset), times(1));
            ledger.verify(() -> TrainerVenueLedger.deferRefund(round), times(1));
            ledger.verify(() -> TrainerVenueLedger.refundBeforePlay(any(), any()), never());
            ledger.verify(() -> TrainerVenueLedger.closeRound(any()), never());
        }
        assertEquals("SHUTDOWN", table.phase);
        assertNull(table.pending);
    }

    @Test void committedLootAtShutdownNeverReturnsStockOrRefundsEntry() throws Exception {
        var table = new TrainerVenueService.Table(mock(MapleMap.class));
        table.round = UUID.randomUUID(); table.phase = "ROUND"; table.player = mock(Character.class);
        MapItem drop = mock(MapItem.class); table.drop = drop;
        when(drop.getCollectedByCharacterId()).thenReturn(123);
        try (var ledger = mockStatic(TrainerVenueLedger.class)) {
            ledger.when(() -> TrainerVenueLedger.closeRound(table.round)).thenReturn(true);
            table.stop();
            ledger.verify(() -> TrainerVenueLedger.returnUnclaimed(any(), any(), any()), never());
            ledger.verify(() -> TrainerVenueLedger.deferRefund(any()), never());
        }
        verify(drop).unlockItem(); assertNull(table.drop);
        assertEquals("SHUTDOWN", table.phase);
    }

    @Test void failedShutdownReturnRetainsRoundAndDropForRecovery() throws Exception {
        var table = new TrainerVenueService.Table(mock(MapleMap.class));
        table.round = UUID.randomUUID(); table.phase = "ROUND";
        MapItem drop = mock(MapItem.class); table.drop = drop;
        when(drop.getVenueAssetId()).thenReturn(UUID.randomUUID());
        try (var ledger = mockStatic(TrainerVenueLedger.class)) {
            ledger.when(() -> TrainerVenueLedger.returnUnclaimed(any(), any(), any())).thenThrow(new SQLException("database offline"));
            assertThrows(SQLException.class, table::stop);
            ledger.verify(() -> TrainerVenueLedger.closeRound(any()), never());
        }
        verify(drop).unlockItem(); assertSame(drop, table.drop); assertNotNull(table.round);
        assertEquals("ROUND", table.phase);
    }

    @Test void cleanupReleasesOnlyExactActorsAndExactLeaseGeneration() {
        MapleMap map = mock(MapleMap.class); Channel channel = mock(Channel.class); PlayerStorage storage = mock(PlayerStorage.class);
        when(map.getChannelServer()).thenReturn(channel); when(channel.getPlayerStorage()).thenReturn(storage);
        Character owned = mock(Character.class), replaced = mock(Character.class), replacement = mock(Character.class);
        when(owned.getId()).thenReturn(9_500_001); when(replaced.getId()).thenReturn(9_500_002);
        when(storage.getCharacterById(9_500_001)).thenReturn(owned);
        when(storage.getCharacterById(9_500_002)).thenReturn(replacement);
        var registry = CompanionTaskService.shared();
        var prior = new CompanionTaskService.PriorActivity("VenueBot", 105040401, 105040401);
        var token = registry.reserveEvent("test-venue", owned.getId(), 0, 1, CompanionTaskService.EventRole.HOST,
                System.currentTimeMillis() + 30_000, prior).orElseThrow();
        var active = registry.commitEvent(token).orElseThrow();
        var table = new TrainerVenueService.Table(map);
        table.bots.add(owned); table.bots.add(replaced); table.leases.add(active);
        try (var generation = mockStatic(BotGeneration.class)) {
            table.cleanupActors(); table.cleanupActors();
            generation.verify(() -> BotGeneration.removeBotFromServer(owned), times(1));
            generation.verify(() -> BotGeneration.removeBotFromServer(replaced), never());
            generation.verify(() -> BotGeneration.removeBotFromServer(replacement), never());
        } finally { registry.releaseEvent(active.botId(), active.generation()); }
        assertTrue(registry.eventLease(owned.getId()).isEmpty());
        assertTrue(table.bots.isEmpty()); assertTrue(table.leases.isEmpty());
    }
}

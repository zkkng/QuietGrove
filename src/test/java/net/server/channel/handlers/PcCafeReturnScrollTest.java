package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import server.ItemInformationProvider;
import server.StatEffect;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;
import server.pccafe.PcCafe;
import testutil.Packets;
import tools.DatabaseConnection;

import java.util.concurrent.atomic.AtomicInteger;
import java.sql.Connection;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PcCafeReturnScrollTest {
    @BeforeAll static void initializeItemsWithoutLiveDatabase() throws Exception {
        Connection connection = mock(Connection.class, RETURNS_DEEP_STUBS);
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            ItemInformationProvider.getInstance();
        }
    }
    static Stream<Integer> roads() {
        return PcCafe.roads().stream().map(PcCafe.Road::map);
    }

    @ParameterizedTest @MethodSource("roads")
    void everyCafeRoadWarpsToLobbyAndConsumesOneScroll(int road) {
        run(road, 2030000, true, true, true, true);
    }

    @Test void namedTownScrollAlsoReturnsToCafeLobby() {
        run(190000000, 2030001, true, true, true, true);
    }
    @Test void unavailableLobbyDoesNotConsumeScroll() {
        run(190000000, 2030000, true, true, false, false);
    }
    @Test void rejectedWarpDoesNotConsumeScroll() {
        run(190000000, 2030000, true, true, true, false);
    }
    @Test void alreadyInLobbyDoesNotConsumeScroll() {
        run(PcCafe.HUB, 2030000, true, true, true, false);
    }
    @Test void deadCharacterCannotWarpOrSpendScroll() {
        run(190000000, 2030000, false, true, true, false);
    }
    @Test void forgedSlotItemCannotWarpOrSpendScroll() {
        run(190000000, 2030000, true, false, true, false);
    }
    @Test void normalMapRetainsOriginalScrollEffect() {
        run(100000000, 2030000, true, true, true, true);
    }
    @Test void cafeMembershipDoesNotMatchUnrelatedMapIds() {
        assertFalse(PcCafe.isCafeMap(190000099));
        assertFalse(PcCafe.isCafeMap(194000000));
        assertFalse(PcCafe.isCafeMap(100000000));
        assertTrue(PcCafe.isCafeMap(PcCafe.HUB));
    }

    private void run(int origin, int itemId, boolean alive, boolean matching,
                     boolean lobbyAvailable, boolean succeeds) {
        Client client = mock(Client.class);
        Character chr = mock(Character.class);
        Inventory inventory = mock(Inventory.class);
        ItemInformationProvider info = mock(ItemInformationProvider.class);
        StatEffect effect = mock(StatEffect.class);
        Channel channel = mock(Channel.class);
        MapManager maps = mock(MapManager.class);
        MapleMap lobby = mock(MapleMap.class);
        Portal portal = mock(Portal.class);
        AtomicInteger currentMap = new AtomicInteger(origin);
        when(client.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(alive);
        when(chr.getMapId()).thenAnswer(call -> currentMap.get());
        when(chr.getInventory(InventoryType.USE)).thenReturn(inventory);
        when(inventory.getItem((short) 1)).thenReturn(
                new Item(matching ? itemId : 2000000, (short) 1, (short) 2));
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getMapFactory()).thenReturn(maps);
        when(maps.getMap(PcCafe.HUB)).thenReturn(lobbyAvailable ? lobby : null);
        when(lobby.getPortal(0)).thenReturn(portal);
        when(info.getItemEffect(itemId)).thenReturn(effect);
        when(effect.applyTo(chr)).thenReturn(succeeds);
        doAnswer(call -> { if (succeeds) currentMap.set(PcCafe.HUB); return null; })
                .when(chr).changeMap(lobby, portal);
        try (var ii = mockStatic(ItemInformationProvider.class);
             var manipulation = mockStatic(InventoryManipulator.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            new UseItemHandler().handlePacket(Packets.buildInPacket(out -> {
                out.writeInt(0); out.writeShort(1); out.writeInt(itemId);
            }), client);
            if (alive && matching && succeeds) {
                manipulation.verify(() -> InventoryManipulator.removeFromSlot(
                        client, InventoryType.USE, (short) 1, (short) 1, false));
            } else {
                manipulation.verifyNoInteractions();
            }
        }
        if (alive && matching && origin != PcCafe.HUB && PcCafe.isCafeMap(origin) && lobbyAvailable) {
            verify(chr).changeMap(lobby, portal);
        } else {
            verify(chr, never()).changeMap(any(MapleMap.class), any(Portal.class));
        }
        if (alive && matching && !PcCafe.isCafeMap(origin)) verify(effect).applyTo(chr);
        else verify(effect, never()).applyTo(chr);
    }
}

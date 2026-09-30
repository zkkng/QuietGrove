package server.maps;

import client.Character;
import client.Client;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import java.awt.Point;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HeadlessMapTransitionTest {
    private static MapleMap scene(int id, World world) throws Exception {
        MapleMap map = spy(new MapleMap(id,0,1,100000000,1));
        doReturn(world).when(map).getWorldServer();
        doReturn(mock(Channel.class)).when(map).getChannelServer();
        map.setOnFirstUserEnter("");
        map.setOnUserEnter("");
        var monitor = MapleMap.class.getDeclaredField("itemMonitor");
        monitor.setAccessible(true);
        monitor.set(map,mock(java.util.concurrent.ScheduledFuture.class));
        return map;
    }
    @Test void actualHeadlessSpawnAndCanonicalMapTransferCompleteWithoutAClientAck() throws Exception {
        var client = mock(Client.class);
        var actor = Character.getDefault(client);
        actor.setID(24001);actor.setName("MapEntryBot");
        when(client.getPlayer()).thenReturn(actor);
        var channel = mock(Channel.class,RETURNS_DEEP_STUBS);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage().getCharacterById(24001)).thenReturn(actor);
        when(client.getChannel()).thenReturn(1);
        var world = mock(World.class);
        var first = scene(100000000,world);
        var next = scene(100000001,world);
        when(channel.getMapFactory().getMap(100000001)).thenReturn(next);
        try(var server = mockStatic(Server.class)) {
            var backend = mock(Server.class);
            when(backend.getWorld(0)).thenReturn(world);
            server.when(Server::getInstance).thenReturn(backend);
            actor.setMap(first);
            assertTrue(actor.isChangingMaps());
            first.addPlayer(actor);
            assertFalse(actor.isChangingMaps());
            assertTrue(first.getCharacters().contains(actor));
            var beforeTransfer=first.getCharacters();
            assertSame(beforeTransfer,first.getCharacters()); // Stable population reuses one immutable snapshot.
            actor.changeMap(next,new Point(0,0));
            assertSame(next,actor.getMap());
            assertFalse(actor.isChangingMaps());
            assertFalse(first.getCharacters().contains(actor));
            assertTrue(beforeTransfer.contains(actor)); // Iterators survive a concurrent departure.
            assertThrows(UnsupportedOperationException.class,()->beforeTransfer.clear());
            assertTrue(next.getCharacters().contains(actor));
        }
    }
    @Test void ordinaryHumanMapEntryStillWaitsForItsRealClientAck() throws Exception {
        var client = mock(Client.class);
        var actor = Character.getDefault(client);
        actor.setID(5);actor.setName("MapEntryHuman");
        when(client.getPlayer()).thenReturn(actor);
        var map = scene(100000000,mock(World.class));
        try(var server = mockStatic(Server.class)) {
            server.when(Server::getInstance).thenReturn(mock(Server.class));
            actor.setMap(map);
            map.addPlayer(actor);
            assertTrue(actor.isChangingMaps());
            actor.setMapTransitionComplete();
            assertFalse(actor.isChangingMaps());
        }
    }
}

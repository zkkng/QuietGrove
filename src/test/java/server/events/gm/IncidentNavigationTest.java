package server.events.gm;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import server.maps.*;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import java.awt.Point;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncidentNavigationTest {
    @Test void headlessRetreatUsesTheActorsRealPortalWithoutTheSharedClientsHumanBinding() {
        var actor=mock(Character.class);var client=mock(Client.class);var source=mock(MapleMap.class);
        var target=mock(MapleMap.class);var channel=mock(Channel.class,RETURNS_DEEP_STUBS);
        var exit=mock(Portal.class);var arrival=mock(Portal.class);
        when(actor.isAlive()).thenReturn(true);when(actor.getClient()).thenReturn(client);
        when(actor.getMap()).thenReturn(source);when(actor.getPosition()).thenReturn(new Point(10,20));
        when(source.getId()).thenReturn(100000000);when(source.getPortals()).thenReturn(List.of(exit));
        when(source.getChannelServer()).thenReturn(channel);when(exit.getPortalStatus()).thenReturn(true);
        when(exit.getPosition()).thenReturn(new Point(10,20));when(exit.getTargetMapId()).thenReturn(100000001);
        when(exit.getTarget()).thenReturn("arrival");when(target.getPortal("arrival")).thenReturn(arrival);
        when(channel.getMapFactory().getMap(100000001)).thenReturn(target);
        doAnswer(i->{when(actor.getMap()).thenReturn(target);return null;}).when(actor).changeMap(target,arrival);
        try(var movement=mockStatic(GCMovement.class);var versions=mockStatic(soloMapling.server.MapleVersionManager.class)) {
            versions.when(()->soloMapling.server.MapleVersionManager.isPortalinCurrentVersion(100000001)).thenReturn(true);
            assertNull(client.getPlayer());assertTrue(IncidentNavigation.enter(actor,exit));
            verify(actor).changeMap(target,arrival);verify(exit,never()).enterPortal(any());
            when(actor.getMap()).thenReturn(source);when(actor.getPosition()).thenReturn(new Point(1000,20));
            assertFalse(IncidentNavigation.enter(actor,exit));
            when(actor.getPosition()).thenReturn(new Point(10,20));when(exit.getScriptName()).thenReturn("questGate");
            assertFalse(IncidentNavigation.enter(actor,exit));when(exit.getScriptName()).thenReturn(null);
            when(exit.getPortalStatus()).thenReturn(false);assertFalse(IncidentNavigation.enter(actor,exit));
            when(exit.getPortalStatus()).thenReturn(true);when(target.getPortal("arrival")).thenReturn(null);
            assertFalse(IncidentNavigation.enter(actor,exit));
            verify(actor,times(1)).changeMap(target,arrival);
        }
    }
}

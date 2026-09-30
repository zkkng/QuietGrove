package net.server.world;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartyJoinIntegrationTest {
    @Test void concurrentPartyCreationPublishesOnePartyAndOneMapMembership() throws Exception {
        Character character = mock(Character.class);
        World world = mock(World.class); MapleMap map = mock(MapleMap.class);
        client.Client connection = mock(client.Client.class);
        when(character.getClient()).thenReturn(connection); when(character.getJob()).thenReturn(client.Job.WARRIOR);
        when(character.getId()).thenReturn(1); when(character.getLevel()).thenReturn(30);
        when(character.getWorldServer()).thenReturn(world); when(character.getMap()).thenReturn(map);
        java.util.concurrent.atomic.AtomicReference<Party> current = new java.util.concurrent.atomic.AtomicReference<>();
        when(character.getParty()).thenAnswer(call -> current.get());
        doAnswer(call -> {current.set(call.getArgument(0)); return null;}).when(character).setParty(any());
        when(world.createParty(any())).thenAnswer(call -> new Party(7, call.getArgument(0)));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = pool.submit(() -> {start.await(); return Party.createParty(character,true);});
            var second = pool.submit(() -> {start.await(); return Party.createParty(character,true);});
            start.countDown();
            assertTrue(first.get() ^ second.get());
        }
        verify(world,times(1)).createParty(any()); verify(map,times(1)).addPartyMember(character,7);
    }
    @Test void rejectedCanonicalAdmissionNeverLeaksMapMembershipOrSuccessEffects() {
        Character character = mock(Character.class);
        World world = mock(World.class);
        Party party = mock(Party.class);
        MapleMap map = mock(MapleMap.class);
        when(character.getWorldServer()).thenReturn(world);
        when(character.getMap()).thenReturn(map);
        when(world.getParty(7)).thenReturn(party);
        when(party.getId()).thenReturn(7);
        when(world.tryJoinParty(character, party)).thenReturn(false);
        assertFalse(Party.joinParty(character, 7, true));
        verify(map, never()).addPartyMember(any(), anyInt());
        verify(character, never()).receivePartyMemberHP();
    }
    @Test void successfulCanonicalAdmissionRunsMapAndHpUpdatesOnce() {
        Character character = mock(Character.class);
        World world = mock(World.class);
        Party party = mock(Party.class);
        MapleMap map = mock(MapleMap.class);
        when(character.getWorldServer()).thenReturn(world);
        when(character.getMap()).thenReturn(map);
        when(world.getParty(7)).thenReturn(party);
        when(party.getId()).thenReturn(7);
        when(world.tryJoinParty(character, party)).thenReturn(true);
        assertTrue(Party.joinParty(character, 7, true));
        verify(map).addPartyMember(character, 7);
        verify(character).receivePartyMemberHP();
        verify(world, never()).updateParty(anyInt(), eq(PartyOperation.JOIN), any());
    }
}

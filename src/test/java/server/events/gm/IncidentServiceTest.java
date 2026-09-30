package server.events.gm;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import server.TimerManager;
import server.life.*;
import server.maps.*;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IncidentServiceTest {
    @Test void disconnectedHumanHostDoesNotStartAnIncident() {
        var host=mock(Character.class);
        when(host.gmLevel()).thenReturn(3);
        when(host.getMap()).thenReturn(mock(MapleMap.class));
        assertEquals("Incident host is disconnected.",IncidentService.getInstance().create(host,"snail",1,false));
    }
    @Test void responderRecoveryWaitsForActualReturnWarp() {
        var actor=mock(Character.class);var origin=mock(MapleMap.class);var town=mock(MapleMap.class);
        var portal=mock(Portal.class);var position=new AtomicReference<MapleMap>(origin);
        when(actor.getMap()).thenAnswer(i->position.get());when(origin.getReturnMap()).thenReturn(town);
        when(actor.getCurrentMaxHp()).thenReturn(100);
        assertFalse(IncidentService.returnDeadResponder(actor));
        verify(actor,never()).updateHp(anyInt());
        when(town.getPortal(0)).thenReturn(portal);
        doThrow(new IllegalStateException("temporary warp failure")).doAnswer(i->{position.set(town);return null;})
                .when(actor).changeMap(town,portal);
        assertFalse(IncidentService.returnDeadResponder(actor));
        assertEquals(origin,actor.getMap());
        assertTrue(IncidentService.returnDeadResponder(actor));
        assertEquals(town,actor.getMap());
        verify(actor).updateHp(30);
    }
    @Test void deadBystanderIsNotReleasedUntilReturnWarpReallySucceeds() throws Exception {
        var service=IncidentService.getInstance();
        var field=IncidentService.class.getDeclaredField("ambientDead");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer,Long> dead=(Map<Integer,Long>)field.get(service);
        var bot=mock(BotSM.class);var actor=mock(Character.class);var origin=mock(MapleMap.class);
        var town=mock(MapleMap.class);var portal=mock(Portal.class);
        var position=new AtomicReference<MapleMap>(origin);
        when(bot.getChr()).thenReturn(actor);when(actor.getId()).thenReturn(71117);
        when(actor.isAlive()).thenReturn(false);when(actor.getMap()).thenAnswer(i->position.get());
        when(actor.getCurrentMaxHp()).thenReturn(100);when(origin.getReturnMap()).thenReturn(town);
        dead.put(71117,0L);
        try(var movement=mockStatic(GCMovement.class)) {
            assertTrue(IncidentService.handleAmbientDeath(bot));
            assertTrue(dead.containsKey(71117));
            when(town.getPortal(0)).thenReturn(portal);
            assertTrue(IncidentService.handleAmbientDeath(bot));
            assertTrue(dead.containsKey(71117));
            doAnswer(i->{position.set(town);return null;}).when(actor).changeMap(town,portal);
            assertTrue(IncidentService.handleAmbientDeath(bot));
            assertFalse(dead.containsKey(71117));
            verify(actor).updateHp(30);
            verify(bot).waitFor(60_000);
        } finally {dead.remove(71117);}
    }
    @Test void actualResponderTravelHistogramHasNonoverlappingBoundaries() {
        assertEquals(0,IncidentService.travelBucket(0));
        assertEquals(0,IncidentService.travelBucket(4_999));
        assertEquals(1,IncidentService.travelBucket(5_000));
        assertEquals(2,IncidentService.travelBucket(15_000));
        assertEquals(3,IncidentService.travelBucket(30_000));
        assertEquals(4,IncidentService.travelBucket(60_000));
        assertThrows(IllegalArgumentException.class,()->IncidentService.travelBucket(-1));
    }
    @Test void finiteSpawnRootsCancellationAndLateRevivesPreserveUnrelatedSameTemplate() throws Exception {
        var service=IncidentService.getInstance();var channel=mock(Channel.class);var map=mock(MapleMap.class);
        var manager=mock(MapManager.class);var host=mock(Character.class);var client=mock(Client.class);var portal=mock(Portal.class);
        when(host.gmLevel()).thenReturn(3);when(host.getMap()).thenReturn(map);when(host.getWorld()).thenReturn(912);
        when(host.getClient()).thenReturn(client);when(host.getPosition()).thenReturn(new Point());when(client.getChannelServer()).thenReturn(channel);
        when(map.getId()).thenReturn(100000000);when(map.getWorld()).thenReturn(912);when(map.getChannelServer()).thenReturn(channel);
        when(map.getPointBelow(any())).thenReturn(new Point());when(map.getMapArea()).thenReturn(new Rectangle(-100,-100,200,200));when(map.getPortal(0)).thenReturn(portal);
        when(channel.getId()).thenReturn(1);when(channel.getWorld()).thenReturn(912);when(channel.getMapFactory()).thenReturn(manager);
        when(manager.getMap(100000000)).thenReturn(map);
        List<Monster> monsters=new ArrayList<>();when(map.getAllMonsters()).thenAnswer(i->List.copyOf(monsters));
        when(map.getCharacters()).thenReturn(List.of());
        var timers=mock(TimerManager.class);
        when(timers.register(any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(ScheduledFuture.class));
        doAnswer(i->{Monster m=i.getArgument(0);if(service.allowSpawn(map,m)){m.setMap(map);m.setObjectId(100+monsters.size());monsters.add(m);service.monsterSpawned(m);}return null;})
                .when(map).spawnMonsterOnGroundBelow(any(Monster.class),any());
        doAnswer(i->{monsters.remove(i.getArgument(0));return null;}).when(map).killMonster(any(Monster.class),isNull(),eq(false),eq(1),eq((short)0));
        try(var life=mockStatic(LifeFactory.class);var storage=mockStatic(CharacterStorage.class);var timer=mockStatic(TimerManager.class)) {
            life.when(()->LifeFactory.getMonster(anyInt())).thenAnswer(i->{MonsterStats stats=new MonsterStats();stats.setHp(100);return new Monster(i.getArgument(0),stats);});
            storage.when(CharacterStorage::getAllBots).thenReturn(Map.of());timer.when(TimerManager::getInstance).thenReturn(timers);
            assertTrue(service.create(host,"snail",31,true).contains("at most3 bosses or30 ordinary"));
            String result=service.create(host,"snail",2,false);assertTrue(result.startsWith("Created silent"),result);assertEquals(1,monsters.size());
            var incidentPump=IncidentService.class.getDeclaredMethod("pump");incidentPump.setAccessible(true);
            incidentPump.invoke(service);assertEquals(1,monsters.size());
            var incidentsField=IncidentService.class.getDeclaredField("incidents");incidentsField.setAccessible(true);
            Object incident=((Map<?,?>)incidentsField.get(service)).values().iterator().next();
            var nextSpawn=incident.getClass().getDeclaredField("nextSpawnAt");nextSpawn.setAccessible(true);
            nextSpawn.setLong(incident,0);incidentPump.invoke(service);assertEquals(2,monsters.size());
            Monster owned=monsters.getFirst(),unrelated=new Monster(100100,owned.getStats());unrelated.setMap(map);monsters.add(unrelated);
            String id=owned.getIncidentOwner();assertNotNull(id);assertNotEquals(owned.getEncounterId(),unrelated.getEncounterId());
            Monster revive=new Monster(100100,owned.getStats());revive.inheritEncounter(owned);assertTrue(service.allowSpawn(map,revive));
            assertTrue(service.cancel(host,id).startsWith("Incident cancelled"));assertEquals(List.of(unrelated),monsters);
            assertFalse(service.allowSpawn(map,revive));assertTrue(service.allowSpawn(map,unrelated));assertFalse(service.owns(id));
            revive.setMap(map);service.monsterSpawned(revive);verify(map).killMonster(revive,null,false,1,(short)0);
            verify(map,never()).killMonster(eq(unrelated),isNull(),eq(false),eq(1),eq((short)0));
            assertTrue(service.create(host,"zakum",1,false).contains("reviewed arena"));
            assertTrue(service.create(host,"snail",257,true).contains("development monster budget"));
        } finally {service.cancelChannel(channel);}
    }
}

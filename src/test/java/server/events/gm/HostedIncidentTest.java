package server.events.gm;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import server.TimerManager;
import server.life.*;
import server.maps.*;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostedIncidentTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void delayedBossRootsAndDescendantsRespectCapsAndCancellationCannotSpawnLate(boolean mixed) throws Exception {
        var service=IncidentService.getInstance();var channel=mock(Channel.class);var map=mock(MapleMap.class);
        var maps=mock(MapManager.class);var host=mock(Character.class);var client=mock(Client.class);var portal=mock(Portal.class);
        when(host.getId()).thenReturn(998321);when(host.getMap()).thenReturn(map);when(host.getWorld()).thenReturn(933);
        when(host.getClient()).thenReturn(client);when(client.getChannelServer()).thenReturn(channel);
        when(map.getId()).thenReturn(100000000);when(map.getWorld()).thenReturn(933);when(map.getChannelServer()).thenReturn(channel);
        when(map.getPointBelow(any())).thenReturn(new Point());when(map.getMapArea()).thenReturn(new Rectangle(-100,-100,200,200));
        when(map.getPortal(0)).thenReturn(portal);when(map.getCharacters()).thenReturn(List.of());
        when(channel.getId()).thenReturn(1);when(channel.getWorld()).thenReturn(933);when(channel.getMapFactory()).thenReturn(maps);
        when(maps.getMap(100000000)).thenReturn(map);
        List<Monster> monsters=new ArrayList<>();when(map.getAllMonsters()).thenAnswer(i->List.copyOf(monsters));
        doAnswer(i->{Monster m=i.getArgument(0);if(service.allowSpawn(map,m)){m.setMap(map);m.setObjectId(100+monsters.size());monsters.add(m);service.monsterSpawned(m);}return null;})
                .when(map).spawnMonsterOnGroundBelow(any(Monster.class),any());
        doAnswer(i->{Monster m=i.getArgument(0);monsters.remove(m);service.monsterRemoved(m,false);return null;})
                .when(map).killMonster(any(Monster.class),isNull(),eq(false),eq(1),eq((short)0));
        var timers=mock(TimerManager.class);when(timers.register(any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(ScheduledFuture.class));
        var completed=new AtomicReference<Boolean>();
        var completionThread=new AtomicReference<Thread>();
        var completionLatch=new java.util.concurrent.CountDownLatch(1);
        java.util.function.Consumer<Boolean> completion=won->{completed.set(won);completionThread.set(Thread.currentThread());completionLatch.countDown();};
        String owner="hosted-test-933";
        var reservation=CompanionTaskService.shared().reserveEvent(owner,host.getId(),933,1,CompanionTaskService.EventRole.HOST,
                System.currentTimeMillis()+15_000,new CompanionTaskService.PriorActivity("SOCIAL_BOT",100000000,100000000)).orElseThrow();
        var lease=CompanionTaskService.shared().commitEvent(reservation).orElseThrow();
        assertTrue(EventMapLeases.acquire(owner,List.of(map)));
        var waveService=mock(WaveInvasionService.class);when(waveService.ownsHost(owner,host)).thenReturn(true);
        try(var life=mockStatic(LifeFactory.class);var storage=mockStatic(CharacterStorage.class);var timer=mockStatic(TimerManager.class);
            var waves=mockStatic(WaveInvasionService.class);var controllers=mockStatic(BossMonsterController.class);
            var bots=mockStatic(soloMapling.ArtificialPlayer.BotHelpers.class)) {
            life.when(()->LifeFactory.getMonster(anyInt())).thenAnswer(i->{MonsterStats stats=new MonsterStats();stats.setHp(100);stats.setBoss(true);return new Monster(i.getArgument(0),stats);});
            storage.when(CharacterStorage::getAllBots).thenReturn(Map.of());timer.when(TimerManager::getInstance).thenReturn(timers);
            waves.when(WaveInvasionService::getInstance).thenReturn(waveService);
            int limit=mixed?10:3;String key=mixed?"wave-bosses":"mushmom";
            assertTrue(service.createHosted(host,owner,key,limit+1,80,new Point(),completion).contains("at most"+limit));
            assertTrue(service.createHosted(host,owner,key,limit,80,new Point(),completion).startsWith("Created "));
            bots.when(()->soloMapling.ArtificialPlayer.BotHelpers.isBot(host)).thenReturn(true);
            assertEquals(0,IncidentService.exposureGeneration(host));
            var bystander=mock(Character.class);when(bystander.getId()).thenReturn(998322);when(bystander.getMap()).thenReturn(map);
            bots.when(()->soloMapling.ArtificialPlayer.BotHelpers.isBot(bystander)).thenReturn(true);
            assertTrue(IncidentService.exposureGeneration(bystander)>0);
            var foreignReservation=CompanionTaskService.shared().reserveEvent("another-show",bystander.getId(),933,1,
                    CompanionTaskService.EventRole.HOST,System.currentTimeMillis()+15_000,lease.prior()).orElseThrow();
            var foreignLease=CompanionTaskService.shared().commitEvent(foreignReservation).orElseThrow();
            try {assertTrue(IncidentService.exposureGeneration(bystander)>0);}
            finally {CompanionTaskService.shared().releaseEvent(bystander.getId(),foreignLease.generation());}
            when(waveService.ownsHost(owner,host)).thenReturn(false);
            assertTrue(IncidentService.exposureGeneration(host)>0); // A stale HOST lease is not staff authority.
            when(waveService.ownsHost(owner,host)).thenReturn(true);
            assertEquals(1,monsters.size());String incidentId=monsters.getFirst().getIncidentOwner();
            var incidentsField=IncidentService.class.getDeclaredField("incidents");incidentsField.setAccessible(true);
            Object incident=((Map<?,?>)incidentsField.get(service)).get(incidentId);
            var nextSpawn=incident.getClass().getDeclaredField("nextSpawnAt");nextSpawn.setAccessible(true);
            var pump=IncidentService.class.getDeclaredMethod("pump");pump.setAccessible(true);
            pump.invoke(service);assertEquals(1,monsters.size());
            nextSpawn.setLong(incident,0);pump.invoke(service);assertEquals(2,monsters.size());
            pump.invoke(service);assertEquals(2,monsters.size());
            nextSpawn.setLong(incident,0);pump.invoke(service);assertEquals(3,monsters.size());
            for(int n=3;n<limit;n++) {nextSpawn.setLong(incident,0);pump.invoke(service);assertEquals(n+1,monsters.size());}
            Monster parent=monsters.getFirst();
            MonsterStats bossStats=new MonsterStats();bossStats.setHp(100);bossStats.setBoss(true);
            var extraBoss=new Monster(8150000,bossStats);extraBoss.inheritEncounter(parent);
            assertFalse(service.allowSpawn(map,extraBoss)); // Same inherited root cannot hide a fourth field boss.
            if(mixed) {
                for(Monster dead:List.copyOf(monsters.subList(0,4))) {dead.setHpZero();monsters.remove(dead);service.monsterRemoved(dead,true);}
                var budgetField=incident.getClass().getDeclaredField("waveBudget");budgetField.setAccessible(true);
                var budget=(BossWaveBudget)budgetField.get(incident);
                var due=BossWaveBudget.class.getDeclaredField("due");due.setAccessible(true);due.setLong(budget,0);
                nextSpawn.setLong(incident,0);pump.invoke(service);
                var queued=incident.getClass().getDeclaredField("remainingInitial");queued.setAccessible(true);
                int total=monsters.size()+queued.getInt(incident);
                assertTrue(total>=7 && total<=10);assertNull(completed.get());
                while(queued.getInt(incident)>0) {nextSpawn.setLong(incident,0);pump.invoke(service);}
                assertEquals(total,monsters.size());assertTrue(monsters.size()<=10);
            }
            for(int n=0;n<30;n++) {
                MonsterStats stats=new MonsterStats();stats.setHp(10);var child=new Monster(100100,stats);child.inheritEncounter(parent);
                assertTrue(service.allowSpawn(map,child));child.setMap(map);child.setObjectId(200+n);monsters.add(child);service.monsterSpawned(child);
            }
            MonsterStats stats=new MonsterStats();stats.setHp(10);var overflow=new Monster(100100,stats);overflow.inheritEncounter(parent);
            assertFalse(service.allowSpawn(map,overflow));
            service.cancelHosted(owner);assertTrue(monsters.isEmpty());assertFalse(service.owns(incidentId));
            assertTrue(completionLatch.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE,completed.get());
            assertFalse(completionThread.get().isVirtual());
            assertTrue(completionThread.get().getName().startsWith("boss-event-tick-"));
            assertTrue(EventMapLeases.owned(map,owner)); // Child cancellation cannot release the host's map.
            nextSpawn.setLong(incident,0);pump.invoke(service);assertTrue(monsters.isEmpty());
            assertFalse(service.allowSpawn(map,overflow));
        } finally {
            service.cancelChannel(channel);EventMapLeases.release(owner);
            CompanionTaskService.shared().releaseEvent(host.getId(),lease.generation());
        }
    }
}

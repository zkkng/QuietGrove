package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import server.life.*;
import server.TimerManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import java.awt.Point;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossControllerTest {
    @Test void laterWaveRootKeepsExistingActorGenerationAndRejectsForeignOrStaleRoots() {
        var tasks=new CompanionTaskService(System::currentTimeMillis,30);
        var bot=mock(Character.class);var map=mock(MapleMap.class);
        when(bot.getId()).thenReturn(20042);when(bot.getMap()).thenReturn(map);when(bot.isAlive()).thenReturn(true);
        var first=mock(Monster.class);var second=mock(Monster.class);var foreign=mock(Monster.class);
        for(var monster:List.of(first,second,foreign)){when(monster.getMap()).thenReturn(map);when(monster.isAlive()).thenReturn(true);}
        when(first.getEncounterId()).thenReturn(501L);when(second.getEncounterId()).thenReturn(502L);when(foreign.getEncounterId()).thenReturn(503L);
        when(second.getIncidentOwner()).thenReturn("wave-root-test");when(foreign.getIncidentOwner()).thenReturn("other-event");
        when(map.getAllMonsters()).thenReturn(List.of(first,second,foreign));
        var pending=tasks.reserveEvent("wave-root-test",20042,0,1,CompanionTaskService.EventRole.PARTICIPANT,
                System.currentTimeMillis()+10000,new CompanionTaskService.PriorActivity("TRAINING_BOT",100000000,100000000)).orElseThrow();
        var lease=tasks.commitEvent(pending).orElseThrow();
        BossMonsterController.stopDriver();
        try(var registry=mockStatic(CompanionTaskService.class);var timers=mockStatic(TimerManager.class)) {
            registry.when(CompanionTaskService::shared).thenReturn(tasks);
            var timer=mock(TimerManager.class);timers.when(TimerManager::getInstance).thenReturn(timer);
            when(timer.register(any(Runnable.class),eq(250L),eq(250L))).thenReturn(mock(java.util.concurrent.ScheduledFuture.class));
            long generation=BossMonsterController.registerIncident("wave-root-test",map,Set.of(501L),Set.of(20042),System.currentTimeMillis()+10000);
            try {
                assertTrue(BossMonsterController.targetAllowed(bot,first));assertFalse(BossMonsterController.targetAllowed(bot,second));
                assertFalse(BossMonsterController.addIncidentRoot("wave-root-test",generation+1,502L));
                assertFalse(BossMonsterController.addIncidentRoot("other-event",generation,502L));
                assertFalse(BossMonsterController.addIncidentRoot("wave-root-test",generation,503L));
                assertFalse(BossMonsterController.addIncidentRoot("wave-root-test",generation,999L));
                assertTrue(BossMonsterController.addIncidentRoot("wave-root-test",generation,502L));
                assertTrue(BossMonsterController.addIncidentRoot("wave-root-test",generation,502L));
                assertTrue(BossMonsterController.targetAllowed(bot,first));assertTrue(BossMonsterController.targetAllowed(bot,second));
                assertEquals(lease,tasks.eventLease(20042).orElseThrow());assertTrue(BossMonsterController.combatAllowed(bot,lease.generation()));
            } finally {BossMonsterController.releaseIncident("wave-root-test",generation);BossMonsterController.stopDriver();}
            assertFalse(BossMonsterController.addIncidentRoot("wave-root-test",generation,502L));
            assertFalse(BossMonsterController.incidentActor(bot));
        }
    }
    @Test void noHumanControllerProducesOneServerStreamAndArrivalReturnsToClientControl() {
        var map=mock(MapleMap.class); var boss=mock(Monster.class); var bot=mock(Character.class); var human=mock(Character.class);
        var hidden=mock(Character.class); var stats=new MonsterStats();
        when(boss.getId()).thenReturn(2220000); when(boss.getEncounterId()).thenReturn(9401L);
        when(boss.getObjectId()).thenReturn(12); when(boss.getMap()).thenReturn(map); when(boss.isAlive()).thenReturn(true);
        when(boss.getStats()).thenReturn(stats); when(boss.getPosition()).thenReturn(new Point());
        when(bot.getMap()).thenReturn(map); when(bot.isAlive()).thenReturn(true); when(bot.getPosition()).thenReturn(new Point(30,0));
        when(map.getAllMonsters()).thenReturn(List.of(boss)); when(map.getCharacters()).thenReturn(List.of(bot));
        when(human.getMap()).thenReturn(map); when(human.isAlive()).thenReturn(true); when(human.isLoggedinWorld()).thenReturn(true);
        when(hidden.getMap()).thenReturn(map); when(hidden.isAlive()).thenReturn(true); when(hidden.isHidden()).thenReturn(true);
        BossMonsterController.stopDriver();
        try(var helpers=mockStatic(BotHelpers.class); var runtime=mockStatic(CompanionRuntime.class); var timers=mockStatic(TimerManager.class)) {
            var timer=mock(TimerManager.class);
            timers.when(TimerManager::getInstance).thenReturn(timer);
            when(timer.register(any(Runnable.class),eq(250L),eq(250L))).thenReturn(mock(java.util.concurrent.ScheduledFuture.class));
            helpers.when(() -> BotHelpers.isBot(bot)).thenReturn(true);
            runtime.when(() -> CompanionRuntime.active(bot)).thenReturn(true);
            long incident=BossMonsterController.registerIncident("controller-fixture",map,Set.of(9401L),Set.of(42),System.currentTimeMillis()+10000);
            try {
                when(boss.getController()).thenReturn(hidden);
                BossMonsterController.pump();
                verify(boss).aggroRemoveController(); verify(map,times(1)).broadcastMessage(any(Packet.class));
                clearInvocations(map,boss);
                when(map.getCharacters()).thenReturn(List.of(bot,human));
                BossMonsterController.pump();
                verify(boss).aggroRemoveController(); verify(boss).aggroUpdateController();
                verify(map,never()).broadcastMessage(any(Packet.class));
                assertTrue(BossMonsterController.needsServerController(boss));
                when(boss.getController()).thenReturn(human);
                assertFalse(BossMonsterController.needsServerController(boss));
                when(human.getMap()).thenReturn(mock(MapleMap.class));
                assertTrue(BossMonsterController.needsServerController(boss));
            } finally { BossMonsterController.releaseIncident("controller-fixture",incident); BossMonsterController.stopDriver(); }
        }
    }
    @Test void emptyVolunteerAudienceStartsOneControllerDriverWithoutGrantingAttackAuthority() {
        BossMonsterController.stopDriver();
        var map=mock(MapleMap.class); var exposed=mock(Character.class); when(exposed.getMap()).thenReturn(map);
        try(var timers=mockStatic(TimerManager.class)) {
            var timer=mock(TimerManager.class);var future=mock(java.util.concurrent.ScheduledFuture.class);
            timers.when(TimerManager::getInstance).thenReturn(timer);
            when(timer.register(any(Runnable.class),eq(250L),eq(250L))).thenReturn(future);
            long first=BossMonsterController.registerIncident("empty-first",map,Set.of(51L),Set.of(),System.currentTimeMillis()+10000);
            long second=BossMonsterController.registerIncident("empty-second",map,Set.of(52L),Set.of(),System.currentTimeMillis()+10000);
            try {
                BossMonsterController.ensureDriver();
                verify(timer,times(1)).register(any(Runnable.class),eq(250L),eq(250L));
                assertFalse(BossMonsterController.incidentActor(exposed));
                when(future.isDone()).thenReturn(true);BossMonsterController.ensureDriver();
                verify(timer,times(2)).register(any(Runnable.class),eq(250L),eq(250L));
            } finally {
                BossMonsterController.releaseIncident("empty-first",first);BossMonsterController.releaseIncident("empty-second",second);
                BossMonsterController.stopDriver();
            }
        }
    }
    @Test void delayedSkillFenceRejectsReleasedGenerationAndIdenticalIdOnAnotherMap() {
        var tasks=new CompanionTaskService(System::currentTimeMillis,30);
        var map=mock(MapleMap.class); var boss=mock(Monster.class); var bot=mock(Character.class); var human=mock(Character.class);
        when(bot.getId()).thenReturn(20042); when(bot.getMap()).thenReturn(map); when(bot.isAlive()).thenReturn(true);
        when(human.getMap()).thenReturn(map); when(human.isAlive()).thenReturn(true);
        when(map.getCharacters()).thenReturn(List.of(bot,human)); when(boss.getMap()).thenReturn(map);
        var party=new CompanionTaskService.PartyKey(0,7);
        var prior=new CompanionTaskService.PriorActivity("TRAINING_BOT",100000000,100000000);
        var reservation=tasks.reserve(1,20042,party,1,1,1,System.currentTimeMillis()+10000,prior).orElseThrow();
        var task=tasks.commit(reservation,()->true).orElseThrow();
        try(var registry=mockStatic(CompanionTaskService.class)) {
            registry.when(CompanionTaskService::shared).thenReturn(tasks);
            var fence=CompanionMonsterAttacks.actorFence(boss);
            assertTrue(fence.test(bot)); assertTrue(fence.test(human));
            tasks.release(20042,task.generation());
            assertFalse(fence.test(bot));
            var replacement=tasks.reserve(2,20042,party,1,1,1,System.currentTimeMillis()+10000,prior).orElseThrow();
            tasks.commit(replacement,()->true).orElseThrow();
            assertFalse(fence.test(bot));
            when(human.getMap()).thenReturn(mock(MapleMap.class)); assertFalse(fence.test(human));
        }
    }
}

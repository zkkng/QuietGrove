package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import net.server.*;
import net.server.channel.Channel;
import net.server.world.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import java.awt.Point;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual runtime orchestration with mocked server I/O; this is not live client acceptance. */
class BossRuntimeTest {
    private static final class Fixture implements AutoCloseable {
        final BossRuntime bosses=BossRuntime.get();
        final CompanionTaskService tasks=new CompanionTaskService(System::currentTimeMillis,30);
        final CompanionRuntime companions=mock(CompanionRuntime.class);
        final Character human=mock(Character.class),bot=mock(Character.class);
        final MapleMap map=mock(MapleMap.class);
        final Monster target=mock(Monster.class);
        final Channel channel=mock(Channel.class);
        final World world=mock(World.class);
        final Party party=mock(Party.class);
        final Server server=mock(Server.class);
        final PlayerStorage storage=mock(PlayerStorage.class);
        final BotSM actor=mock(BotSM.class);
        final MockedStatic<Server> servers=mockStatic(Server.class);
        final MockedStatic<CompanionTaskService> registries=mockStatic(CompanionTaskService.class);
        final MockedStatic<CompanionRuntime> runtimes=mockStatic(CompanionRuntime.class);
        final MockedStatic<CharacterStorage> bots=mockStatic(CharacterStorage.class);
        final MockedStatic<BotHelpers> helpers=mockStatic(BotHelpers.class);
        final MockedStatic<BossSuitability> suitability=mockStatic(BossSuitability.class);
        final long generation;
        Fixture() {
            servers.when(Server::getInstance).thenReturn(server); when(server.getWorld(0)).thenReturn(world);
            when(world.getPlayerStorage()).thenReturn(storage);when(storage.getCharacterById(101)).thenReturn(human);
            when(human.getId()).thenReturn(101); when(bot.getId()).thenReturn(20071);
            when(human.getWorldServer()).thenReturn(world); when(bot.getWorldServer()).thenReturn(world);
            when(human.getParty()).thenReturn(party);when(bot.getParty()).thenReturn(party);
            when(human.getPartyId()).thenReturn(71);when(bot.getPartyId()).thenReturn(71);
            when(party.getId()).thenReturn(71);when(party.getLeaderId()).thenReturn(101);
            when(human.getMap()).thenReturn(map);when(bot.getMap()).thenReturn(map);
            when(human.getMapId()).thenReturn(104000400);when(bot.getMapId()).thenReturn(104000400);
            when(human.isAlive()).thenReturn(true);when(bot.isAlive()).thenReturn(true);when(human.isLoggedinWorld()).thenReturn(true);
            when(human.getPosition()).thenReturn(new Point());when(bot.getPosition()).thenReturn(new Point());
            when(map.getId()).thenReturn(104000400);when(map.getChannelServer()).thenReturn(channel);when(channel.getId()).thenReturn(77);
            when(map.getCharacters()).thenReturn(List.of(human,bot));when(map.getAllMonsters()).thenReturn(List.of(target));
            when(target.getId()).thenReturn(2220000);when(target.getEncounterId()).thenReturn(7191L);
            when(target.getMap()).thenReturn(map);when(target.isAlive()).thenReturn(true);
            when(actor.getChr()).thenReturn(bot);bots.when(()->CharacterStorage.getBotById(20071)).thenReturn(actor);
            helpers.when(()->BotHelpers.isBot(bot)).thenReturn(true);
            suitability.when(()->BossSuitability.assess(eq(bot),any(),any())).thenReturn(new BossSuitability.Assessment(true,"ready",1,2,null));
            registries.when(CompanionTaskService::shared).thenReturn(tasks);runtimes.when(CompanionRuntime::get).thenReturn(companions);
            runtimes.when(()->CompanionRuntime.ordinaryMap(any())).thenReturn(true);
            var reservation=tasks.reserve(1,20071,new CompanionTaskService.PartyKey(0,71),101,77,1,System.currentTimeMillis()+10000,
                    new CompanionTaskService.PriorActivity("TRAINING_BOT",100000000,100000000)).orElseThrow();
            generation=tasks.commit(reservation,()->true).orElseThrow().generation();
            doAnswer(i->{var task=(CompanionTaskService.Task)i.getArgument(0);tasks.release(task.botId(),task.generation());bosses.detached(task.botId(),task.generation());return null;})
                    .when(companions).release(any());
        }
        void chat(String text) {assertTrue(bosses.chat(human,text,Map.of()));}
        void kill() {BossRuntime.damaged(target,bot,100);BossRuntime.removed(target,true);bosses.pump();}
        public void close() {
            bosses.cancelChannel(0,77);
            suitability.close();helpers.close();bots.close();runtimes.close();registries.close();servers.close();
        }
    }
    @Test void actualOneShotRuntimeReleasesOnceAndNeverDeletesNaturalBoss() {
        try(var f=new Fixture()) {
            f.chat("help fight Mano here");assertTrue(f.bosses.active(f.bot));f.kill();f.bosses.pump();
            assertFalse(f.bosses.active(f.bot));assertTrue(f.tasks.task(20071).isEmpty());
            verify(f.companions,times(1)).release(any());
            verify(f.human,times(1)).dropMessage(eq(5),contains("KILLED_WITH_CONTRIBUTION"));
            verify(f.map,never()).killMonster(any(Monster.class),any(Character.class),anyBoolean(),anyShort());
        }
    }
    @Test void continuingPartyRetainsCanonicalLeaseAfterKillButDismissalReleasesIt() {
        try(var f=new Fixture()) {
            f.chat("hunt Mano and keep partying");f.kill();
            assertEquals(CompanionTaskService.Objective.TRAINING,f.tasks.task(20071).orElseThrow().objective());
            verify(f.companions,never()).release(any());
            // A new continuing hunt can reuse the same canonical companion; dismissal ends ownership.
            f.chat("hunt Mano and keep partying");f.chat("dismiss companions");
            assertTrue(f.tasks.task(20071).isEmpty());verify(f.companions,times(1)).release(any());
        }
    }
    @Test void changingModeReusesCompanionAndDismissalStillRestoresItOnce() {
        try(var f=new Fixture()) {
            f.chat("hunt Mano and keep partying");f.chat("help fight Mano here");
            assertEquals(f.generation,f.tasks.task(20071).orElseThrow().generation());
            assertTrue(f.bosses.active(f.bot));verify(f.companions,never()).release(any());
            f.chat("dismiss companions");assertTrue(f.tasks.task(20071).isEmpty());
            verify(f.companions,times(1)).release(any());
        }
    }
    @Test void namedDismissalReleasesOnlyTheRequestedHelperWithoutCancellingTheHunt() {
        try(var f=new Fixture()) {
            f.chat("hunt Mano and keep partying");
            assertTrue(f.bosses.chat(f.human,"Mira dismiss companions",Map.of(20071,"Mira")));
            assertTrue(f.tasks.task(20071).isEmpty());verify(f.companions,times(1)).release(any());
            assertTrue(f.bosses.chat(f.human,"boss status",Map.of()));
            verify(f.human).dropMessage(eq(5),contains("Mano:"));
            verify(f.companions,never()).cancelBossRequests(anyInt(),any());
        }
    }
    @Test void staleRetreatGenerationCannotMoveOrMutateAReplacementLease() {
        try(var f=new Fixture();var movement=mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            var portal=mock(server.maps.Portal.class);when(portal.getPortalStatus()).thenReturn(true);
            when(portal.getTargetMapId()).thenReturn(104000000);when(portal.getPosition()).thenReturn(new Point(200,0));
            when(f.map.getPortals()).thenReturn(List.of(portal));
            f.chat("help fight Mano here");f.chat("retreat");
            f.tasks.release(20071,f.generation);
            var replacement=f.tasks.reserve(2,20071,new CompanionTaskService.PartyKey(0,71),101,77,1,System.currentTimeMillis()+10000,
                    new CompanionTaskService.PriorActivity("TRAINING_BOT",100000000,100000000)).orElseThrow();
            var current=f.tasks.commit(replacement,()->true).orElseThrow();
            assertFalse(f.bosses.lootTick(f.bot,f.generation));
            assertEquals(current.generation(),f.tasks.task(20071).orElseThrow().generation());
            movement.verifyNoInteractions();verify(f.companions,never()).release(any());
            f.tasks.release(20071,current.generation());
        }
    }
    @Test void logoutCancelsCurrentHuntAndLateRemovalCannotReportASecondOutcome() {
        try(var f=new Fixture()) {
            f.chat("help fight Mano here");when(f.human.isLoggedinWorld()).thenReturn(false);f.bosses.pump();
            assertTrue(f.tasks.task(20071).isEmpty());BossRuntime.removed(f.target,true);f.bosses.pump();
            verify(f.companions,times(1)).release(any());verify(f.companions,times(1)).cancelBossRequests(101,new CompanionTaskService.PartyKey(0,71));
        }
    }
    @Test void historicalMapIndexCannotBindAWorldBossAfterPartyLeaves() {
        try(var f=new Fixture()) {
            when(f.map.getAllMonsters()).thenReturn(List.of());f.chat("hunt Mano");
            var elsewhere=mock(MapleMap.class);when(f.human.getMap()).thenReturn(elsewhere);when(f.bot.getMap()).thenReturn(elsewhere);
            assertFalse(f.bosses.controlEligible(f.target));BossRuntime.damaged(f.target,mock(Character.class),100);
            assertFalse(f.bosses.controlEligible(f.target));
        }
    }
    @Test void retreatStopsAttackAuthorityUntilPhysicalExitAndRestoresOnce() {
        try(var f=new Fixture();var movement=mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            var portal=mock(server.maps.Portal.class);when(portal.getPortalStatus()).thenReturn(true);
            when(portal.getTargetMapId()).thenReturn(104000000);when(portal.getPosition()).thenReturn(new Point(200,0));
            when(portal.getScriptName()).thenReturn("safeExit");when(f.map.getPortals()).thenReturn(List.of(portal));
            f.chat("help fight Mano here");f.chat("retreat");
            assertFalse(f.bosses.combatPermitted(f.bot));assertTrue(f.tasks.task(20071).isPresent());
            verify(f.companions,never()).release(any());
            when(f.bot.getPosition()).thenReturn(new Point(200,0));
            assertTrue(f.bosses.lootTick(f.bot,f.generation));
            assertTrue(f.tasks.task(20071).isEmpty());assertTrue(f.bosses.combatPermitted(f.bot));
            assertFalse(f.bosses.lootTick(f.bot,f.generation));verify(f.companions,times(1)).release(any());
            verify(f.human,times(1)).dropMessage(eq(5),contains("RETREATED"));
        }
    }
    @Test void continuingRetreatKeepsPartyButCannotResumeTrainingAttacksUntilAnotherHunt() {
        try(var f=new Fixture();var movement=mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            var portal=mock(server.maps.Portal.class);when(portal.getPortalStatus()).thenReturn(true);
            when(portal.getTargetMapId()).thenReturn(104000000);when(portal.getPosition()).thenReturn(new Point(200,0));
            when(portal.getScriptName()).thenReturn("safeExit");when(f.map.getPortals()).thenReturn(List.of(portal));
            f.chat("hunt Mano and keep partying");f.chat("retreat");
            assertEquals(CompanionTaskService.Objective.RESTING,f.tasks.task(20071).orElseThrow().objective());
            when(f.bot.getPosition()).thenReturn(new Point(200,0));
            assertTrue(f.bosses.lootTick(f.bot,f.generation));
            assertEquals(CompanionTaskService.Objective.RESTING,f.tasks.task(20071).orElseThrow().objective());
            assertEquals(CompanionTaskService.State.FOLLOW,f.tasks.task(20071).orElseThrow().state());
            verify(f.companions,never()).release(any());
            f.chat("help fight Mano here");
            assertEquals(CompanionTaskService.Objective.FIELD_BOSS,f.tasks.task(20071).orElseThrow().objective());
            f.chat("dismiss companions");verify(f.companions,times(1)).release(any());
        }
    }
    @Test void continuingRetreatWithoutALegalPositionRestsAndRetainsTheLease() {
        try(var f=new Fixture()) {
            f.chat("hunt Mano and keep partying");f.chat("retreat");
            assertEquals(CompanionTaskService.Objective.RESTING,f.tasks.task(20071).orElseThrow().objective());
            assertEquals(CompanionTaskService.State.FOLLOW,f.tasks.task(20071).orElseThrow().state());
            verify(f.companions,never()).release(any());
            // No boss hunt remains: the top-level companion handler must consume dismissal.
            assertFalse(f.bosses.chat(f.human,"dismiss companions",Map.of()));
            assertEquals(RecruitIntentParser.Kind.CANCEL,RecruitIntentParser.parse("dismiss companions",Map.of()).kind());
            f.companions.release(f.tasks.task(20071).orElseThrow());
            verify(f.companions,times(1)).release(any());
        }
    }
    @Test void duplicatePapulatusRootInTheSameInstanceCannotTargetOrCompleteTheHunt() {
        try(var f=new Fixture()) {
            var instance=mock(scripting.event.EventInstanceManager.class);
            var manager=mock(scripting.event.EventManager.class);
            when(instance.getEm()).thenReturn(manager);when(manager.getName()).thenReturn("PapulatusBattle");
            when(f.map.getEventInstance()).thenReturn(instance);
            when(f.bot.getEventInstance()).thenReturn(instance);when(f.human.getEventInstance()).thenReturn(instance);
            when(f.map.getId()).thenReturn(220080001);when(f.bot.getMapId()).thenReturn(220080001);when(f.human.getMapId()).thenReturn(220080001);
            when(f.human.getLevel()).thenReturn(100);
            when(f.target.getId()).thenReturn(8500001);when(f.target.getEncounterRootTemplate()).thenReturn(8500001);
            f.chat("help fight Papulatus here");
            assertTrue(f.bosses.targetAllowed(f.bot,f.target));
            var duplicate=mock(Monster.class);when(duplicate.getMap()).thenReturn(f.map);
            when(duplicate.getId()).thenReturn(8500002);when(duplicate.getEncounterRootTemplate()).thenReturn(8500001);
            when(duplicate.getEncounterId()).thenReturn(9999L);when(duplicate.getParentMobOid()).thenReturn(123);
            assertFalse(f.bosses.targetAllowed(f.bot,duplicate));
            BossRuntime.removed(duplicate,true);f.bosses.pump();
            assertTrue(f.bosses.active(f.bot));verify(f.companions,never()).release(any());
            var finalPhase=mock(Monster.class);when(finalPhase.getMap()).thenReturn(f.map);
            when(finalPhase.getId()).thenReturn(8500002);when(finalPhase.getEncounterRootTemplate()).thenReturn(8500001);
            when(finalPhase.getEncounterId()).thenReturn(7191L);when(finalPhase.getParentMobOid()).thenReturn(123);
            assertTrue(f.bosses.targetAllowed(f.bot,finalPhase));
            BossRuntime.damaged(finalPhase,f.bot,100);BossRuntime.removed(finalPhase,true);f.bosses.pump();
            assertTrue(f.tasks.task(20071).isEmpty());
            verify(f.human,times(1)).dropMessage(eq(5),contains("KILLED_WITH_CONTRIBUTION"));
        }
    }
}

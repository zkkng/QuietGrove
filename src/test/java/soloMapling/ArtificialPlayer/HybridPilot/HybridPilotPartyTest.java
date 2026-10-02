package soloMapling.ArtificialPlayer.HybridPilot;

import client.Character;
import org.junit.jupiter.api.Test;
import server.TimerManager;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.*;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTickService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises real lease/activation/release orchestration, with only server I/O replaced. */
class HybridPilotPartyTest {
    @Test void acceptedAndReleasedPartyKeepsExactActorBodyAndScheduler() throws Exception {
        var tasks = new CompanionTaskService(System::currentTimeMillis, 3);
        Character body = mock(Character.class);
        when(body.getId()).thenReturn(29991);
        when(body.isAlive()).thenReturn(true);
        try (var registries = mockStatic(CompanionTaskService.class);
             var timers = mockStatic(TimerManager.class);
             var controller = mockStatic(BossMonsterController.class);
             var builds = mockStatic(CompanionBuild.class);
             var attacks = mockStatic(BotAttack.class);
             var speech = mockStatic(SocialCommands.class);
             var movement = mockStatic(GCMovement.class);
             var bosses = mockStatic(BossRuntime.class)) {
            registries.when(CompanionTaskService::shared).thenReturn(tasks);
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            bosses.when(BossRuntime::get).thenReturn(mock(BossRuntime.class));
            var constructor = CompanionRuntime.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            var runtime = constructor.newInstance();
            var actor = new HybridPilotBot(body, System::currentTimeMillis, mock(HybridPilotBot.Effects.class));
            CharacterStorage.addActiveBot(body.getId(), actor);
            actor.setRunning(true);
            var key = new CompanionTaskService.PartyKey(0, 19);
            var reservation = tasks.reserve(1, body.getId(), key, 100, 1, 1,
                    System.currentTimeMillis() + 10000,
                    new CompanionTaskService.PriorActivity("HYBRID_PILOT", 100000000, 100000000)).orElseThrow();
            var task = tasks.commit(reservation, () -> true).orElseThrow();
            try {
                runtime.accepted(new RecruitRequestCoordinator.Context(100, key, 1, 1, "party"), task);
                assertEquals(HybridPilotBot.Mode.PARTY, actor.mode());
                assertSame(actor, CharacterStorage.getBotById(body.getId()));
                assertSame(body, actor.getChr());
                assertFalse(BotTickService.isRegistered(body.getId()), "Delegate must never register its own tick");
                assertTrue(tasks.task(body.getId()).isPresent());
                runtime.release(task);
                runtime.release(task);
                assertTrue(tasks.task(body.getId()).isEmpty());
                assertSame(actor, CharacterStorage.getBotById(body.getId()));
                assertEquals(HybridPilotBot.Mode.SOCIAL, actor.mode());
                assertTrue(actor.getRunning());
                verify(body, never()).updateHp(anyInt());
                verify(body, never()).updateMp(anyInt());
            } finally {
                actor.stopScheduledTask();
                CharacterStorage.removeActiveBot(body.getId());
            }
        }
    }
}

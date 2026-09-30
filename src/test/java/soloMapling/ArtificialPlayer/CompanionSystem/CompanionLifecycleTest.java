package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import net.server.world.*;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionLifecycleTest {
    private final CompanionTaskService tasks = new CompanionTaskService(System::currentTimeMillis, 30);
    private final Character bot = mock(Character.class);
    private final World world = mock(World.class);
    private final Party party = mock(Party.class);
    private final MapleMap map = mock(MapleMap.class);
    private final CompanionRuntime runtime = mock(CompanionRuntime.class);
    private CompanionTaskService.Task lease() {
        when(bot.getId()).thenReturn(9001); when(bot.getParty()).thenReturn(party);
        when(bot.getWorldServer()).thenReturn(world); when(bot.getMap()).thenReturn(map);
        when(bot.getCurrentMaxHp()).thenReturn(1000);
        when(party.getId()).thenReturn(7);
        when(party.getMemberById(1)).thenReturn(mock(PartyCharacter.class));
        when(world.getParty(7)).thenReturn(party);
        var reservation = tasks.reserve(1, 9001, new CompanionTaskService.PartyKey(0,7),
                1, 1, 1, System.currentTimeMillis()+15000,
                new CompanionTaskService.PriorActivity("TRAINING_BOT",100,101)).orElseThrow();
        return tasks.commit(reservation, () -> true).orElseThrow();
    }
    @Test void deathAlreadyInReturnTownRevivesAndReleasesOnce() {
        var task = lease(); when(map.getReturnMap()).thenReturn(map);
        try (var registry = mockStatic(CompanionTaskService.class); var build = mockStatic(CompanionBuild.class);
             var api = mockStatic(CompanionRuntime.class); var attacks = mockStatic(BotAttack.class);
             var movement = mockStatic(GCMovement.class)) {
            registry.when(CompanionTaskService::shared).thenReturn(tasks);
            api.when(CompanionRuntime::get).thenReturn(runtime);
            CompanionBot actor = new CompanionBot(bot); actor.setRunning(true); actor.updateState();
            verify(bot).updateHp(300);
            verify(runtime, times(1)).release(task);
            verify(bot, never()).changeMap(any(MapleMap.class), any(server.maps.Portal.class));
            assertEquals(CompanionTaskService.State.DEAD, tasks.task(9001).orElseThrow().state());
        }
    }
    @Test void staleGenerationCannotMoveOrRecoverDeadBot() {
        var task = lease();
        try (var registry = mockStatic(CompanionTaskService.class); var build = mockStatic(CompanionBuild.class);
             var api = mockStatic(CompanionRuntime.class); var attacks = mockStatic(BotAttack.class);
             var movement = mockStatic(GCMovement.class)) {
            registry.when(CompanionTaskService::shared).thenReturn(tasks);
            api.when(CompanionRuntime::get).thenReturn(runtime);
            CompanionBot actor = new CompanionBot(bot); actor.setRunning(true);
            tasks.release(9001, task.generation()); actor.updateState();
            verifyNoInteractions(runtime); movement.verifyNoInteractions(); verify(bot, never()).updateHp(anyInt());
        }
    }
    @Test void failureDuringTownRecoveryReleasesTheMatchingLease() {
        var task = lease(); when(map.getReturnMap()).thenThrow(new IllegalStateException("test route failure"));
        try (var registry = mockStatic(CompanionTaskService.class); var build = mockStatic(CompanionBuild.class);
             var api = mockStatic(CompanionRuntime.class); var attacks = mockStatic(BotAttack.class);
             var movement = mockStatic(GCMovement.class)) {
            registry.when(CompanionTaskService::shared).thenReturn(tasks);
            api.when(CompanionRuntime::get).thenReturn(runtime);
            CompanionBot actor = new CompanionBot(bot); actor.setRunning(true);
            assertThrows(IllegalStateException.class, actor::updateState);
            verify(runtime).release(argThat(t -> t.generation() == task.generation()));
        }
    }
}

package soloMapling.ArtificialPlayer.HybridPilot;

import client.BotClient;
import client.Character;
import client.Client;
import client.Disease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.server.BotTickService;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridPilotBotTest {
    final Character body = mock(Character.class), human = mock(Character.class);
    final MapleMap map = mock(MapleMap.class);
    final HybridPilotBot.Effects effects = mock(HybridPilotBot.Effects.class);
    final AtomicLong now = new AtomicLong(1000);
    HybridPilotBot actor;

    @BeforeEach void setup() {
        when(body.getId()).thenReturn(29001);
        when(body.getName()).thenReturn("Hybrid1");
        when(body.getMap()).thenReturn(map);
        when(body.isAlive()).thenReturn(true);
        when(body.getHp()).thenReturn(100);
        when(body.getCurrentMaxHp()).thenReturn(100);
        when(human.getMap()).thenReturn(map);
        when(human.getClient()).thenReturn(mock(Client.class));
        when(human.getName()).thenReturn("Tester");
        when(map.getCharacters()).thenReturn(List.of(human));
        try (var attacks = mockStatic(BotAttack.class)) { actor = new HybridPilotBot(body, now::get, effects); }
        CharacterStorage.addActiveBot(body.getId(), actor);
        actor.setRunning(true);
    }
    @AfterEach void cleanup() {
        actor.stopScheduledTask();
        CharacterStorage.removeActiveBot(body.getId());
    }

    @Test void sameCharacterSwitchesSocialCombatSocialWithoutTypeReplacement() {
        when(effects.attack(body, map)).thenReturn(false, true, false);
        actor.updateState(); assertEquals(HybridPilotBot.Mode.SOCIAL, actor.mode());
        now.addAndGet(500); actor.updateState(); assertEquals(HybridPilotBot.Mode.COMBAT, actor.mode());
        now.addAndGet(1500); actor.updateState(); assertEquals(HybridPilotBot.Mode.SOCIAL, actor.mode());
        assertSame(body, actor.getChr()); assertSame(actor, CharacterStorage.getBotById(body.getId()));
        verify(body, never()).updateHp(anyInt());
    }
    @Test void emptyMapDoesNotAttackHurtOrTalkAndWakeDoesNotCatchUp() {
        when(map.getCharacters()).thenReturn(List.of());
        actor.updateState(); now.addAndGet(60_000); actor.updateState();
        assertEquals(HybridPilotBot.Mode.DORMANT, actor.mode()); verifyNoInteractions(effects);
        when(map.getCharacters()).thenReturn(List.of(human)); actor.updateState();
        verify(effects, times(1)).attack(body, map); verify(effects, times(1)).contact(body, map);
    }
    @Test void sameMapIdInAnotherInstanceDoesNotWakeActor() {
        MapleMap other = mock(MapleMap.class);
        when(other.getId()).thenReturn(100000000); when(map.getId()).thenReturn(100000000);
        when(human.getMap()).thenReturn(other);
        actor.updateState(); verifyNoInteractions(effects);
        assertFalse(HybridPilotBot.observed(map));
    }
    @Test void headlessCharactersAreNotObserversRegardlessOfId() {
        when(human.getClient()).thenReturn(mock(BotClient.class));
        when(human.getId()).thenReturn(1);
        actor.updateState(); verifyNoInteractions(effects);
    }
    @Test void realClientAboveLegacyIdThresholdStillObserves() {
        when(human.getId()).thenReturn(99999);
        assertTrue(HybridPilotBot.observed(map));
    }
    @Test void contactDeathPreventsAttackAndSpeechInSameTick() {
        assertTrue(actor.chat(human, "Hybrid1 hello"));
        when(effects.contact(body, map)).thenAnswer(inv -> { when(body.isAlive()).thenReturn(false); return true; });
        actor.updateState();
        verify(effects, never()).attack(any(), any()); verify(effects, never()).speak(any(), any());
        assertEquals(HybridPilotBot.Mode.DEAD, actor.mode());
    }
    @Test void deadActorNeverAutoRevivesOrActs() {
        when(body.isAlive()).thenReturn(false); actor.updateState();
        now.addAndGet(30000); actor.updateState(); verifyNoInteractions(effects);
        verify(body, never()).updateHp(anyInt());
    }
    @Test void socialActorStillTakesContactDamage() {
        actor.updateState(); verify(effects).contact(body, map);
        assertEquals(HybridPilotBot.Mode.SOCIAL, actor.mode());
    }
    @Test void attackAndDamageAreRateLimitedIndependently() {
        when(effects.attack(body, map)).thenReturn(true); when(effects.contact(body, map)).thenReturn(true);
        for (int i = 0; i < 3; i++) { actor.updateState(); now.addAndGet(500); }
        verify(effects, times(1)).attack(body, map); verify(effects, times(1)).contact(body, map);
        actor.updateState(); verify(effects, times(2)).attack(body, map); verify(effects, times(2)).contact(body, map);
    }
    @Test void stunnedActorStillTakesDamageButCannotAttack() {
        when(body.hasDisease(Disease.STUN)).thenReturn(true); actor.updateState();
        verify(effects).contact(body, map); verify(effects, never()).attack(any(), any());
        assertEquals(HybridPilotBot.Mode.BLOCKED, actor.mode());
    }
    @Test void chatDuringCombatDoesNotReplaceBrainOrPreventFighting() {
        when(effects.attack(body, map)).thenReturn(true);
        assertTrue(actor.chat(human, "Hybrid1: hello")); actor.updateState();
        verify(effects).attack(body, map); verify(effects).speak(eq(body), contains("Tester"));
        assertEquals(HybridPilotBot.Mode.COMBAT, actor.mode());
    }
    @Test void chatFloodIsOneReplyWithCooldown() {
        for (int i = 0; i < 1000; i++) assertTrue(actor.chat(human, "Hybrid1 hello"));
        actor.updateState();
        for (int i = 0; i < 1000; i++) actor.chat(human, "Hybrid1 hello");
        now.addAndGet(500); actor.updateState();
        verify(effects, times(1)).speak(any(), any());
    }
    @Test void expiredOrDepartedSenderDoesNotReceiveQueuedSpeech() {
        actor.chat(human, "Hybrid1 hello"); now.addAndGet(3000); actor.updateState();
        verify(effects, never()).speak(any(), any());
        actor.chat(human, "Hybrid1 hello"); when(human.getMap()).thenReturn(mock(MapleMap.class));
        actor.updateState(); verify(effects, never()).speak(any(), any());
    }
    @Test void sleepDiscardsSpeechEvenIfSenderReturnsImmediately() {
        actor.chat(human, "Hybrid1 hello"); when(map.getCharacters()).thenReturn(List.of()); actor.updateState();
        when(map.getCharacters()).thenReturn(List.of(human)); actor.updateState();
        verify(effects, never()).speak(any(), any());
    }
    @Test void addressedTradeGetsHonestRefusalAndNamesRequireBoundary() {
        assertFalse(actor.chat(human, "Hybrid12 sell")); assertFalse(actor.chat(human, "hello"));
        assertTrue(actor.chat(human, "hybrid1 sell")); actor.updateState();
        verify(effects).speak(eq(body), contains("Trading isn't part"));
    }
    @Test void stopIsIdempotentAndLateTicksAndChatCannotAct() {
        actor.stopScheduledTask(); actor.stopScheduledTask(); actor.updateState();
        actor.setRunning(true); actor.startScheduledTask(); actor.updateState();
        assertFalse(actor.chat(human, "Hybrid1 hello")); verifyNoInteractions(effects);
        assertFalse(BotTickService.isRegistered(body.getId()));
    }
    @Test void failureStopsInsteadOfRetryingForever() {
        when(effects.contact(body, map)).thenThrow(new IllegalStateException("injected"));
        actor.updateState(); actor.updateState();
        verify(effects, times(1)).contact(body, map); verify(effects, never()).attack(any(), any());
        assertEquals(HybridPilotBot.Mode.FAULTED, actor.mode());
        assertTrue(actor.status().contains("IllegalStateException"));
    }
    @Test void lifetimeExpiresEvenOnEmptyMapAndRemovalIsIdempotent() {
        when(map.getCharacters()).thenReturn(List.of()); now.addAndGet(HybridPilotBot.LIFETIME_MS);
        actor.updateState(); actor.remove(); actor.updateState();
        verify(effects, times(1)).remove(body); verify(effects, never()).attack(any(), any());
        assertTrue(actor.removed());
    }
    @Test void failedRemovalRemainsRetryableButNoFurtherGameplayRuns() {
        doThrow(new IllegalStateException("cleanup")).doNothing().when(effects).remove(body);
        assertThrows(IllegalStateException.class, actor::remove);
        actor.updateState(); verify(effects, never()).attack(any(), any()); assertFalse(actor.removed());
        actor.remove(); assertTrue(actor.removed());
    }
    @Test void ordinaryControllerConversionAndDirectReplacementAreRejected() {
        assertFalse(BotTypeManager.convertBotType(body, BotTypeManager.BotType.TRAINING_BOT));
        for (var type : BotTypeManager.BotType.values())
            assertThrows(IllegalStateException.class, () -> type.createAndSetBot(body));
        assertThrows(IllegalStateException.class, () -> CharacterStorage.addActiveBot(body.getId(), mock(BotSM.class)));
        assertSame(actor, CharacterStorage.getBotById(body.getId()));
    }
    @Test void pilotCannotEnterLegacyTradeThroughAnyEntryPoint() {
        server.Trade.startTrade(body);
        server.Trade.inviteTrade(human, body);
        server.Trade.visitTrade(human, body);
        verify(body, never()).setTrade(any());
        verify(human, times(2)).message("Hybrid pilot bots do not trade yet.");
    }
    @Test void eventControllersCannotRecruitPilot() {
        assertNull(server.events.gm.EventBotRuntime.prior(actor));
        assertFalse(server.events.gm.EventBotRuntime.eligible(actor, 0, 1));
        assertFalse(actor.isAvailableForAmbientActions());
    }
    @Test void manualRelocationPreservesHpAndControllerAndClearsOldConversation() {
        var channel = mock(net.server.channel.Channel.class);
        MapleMap destination = mock(MapleMap.class);
        when(map.getChannelServer()).thenReturn(channel); when(destination.getChannelServer()).thenReturn(channel);
        when(human.getPosition()).thenReturn(new java.awt.Point(40, 80));
        actor.chat(human, "Hybrid1 hello"); when(human.getMap()).thenReturn(destination);
        assertTrue(actor.relocate(human));
        verify(body).changeMap(destination, new java.awt.Point(40, 80));
        verify(body, never()).updateHp(anyInt()); assertSame(actor, CharacterStorage.getBotById(body.getId()));
        when(human.getMap()).thenReturn(map); actor.updateState(); verify(effects, never()).speak(any(), any());
    }
    @Test void relocationRefusesCrossChannelAndRetiredActor() {
        MapleMap other = mock(MapleMap.class);
        when(map.getChannelServer()).thenReturn(mock(net.server.channel.Channel.class));
        when(other.getChannelServer()).thenReturn(mock(net.server.channel.Channel.class));
        when(human.getMap()).thenReturn(other); assertFalse(actor.relocate(human));
        when(human.getMap()).thenReturn(map); actor.stopScheduledTask(); assertFalse(actor.relocate(human));
        verify(body, never()).changeMap(any(MapleMap.class), any(java.awt.Point.class));
    }
    @Test void eventMapEvenWithoutCharacterEventRegistrationIsBlocked() {
        when(map.getEventInstance()).thenReturn(mock(scripting.event.EventInstanceManager.class));
        actor.updateState(); verifyNoInteractions(effects); assertEquals(HybridPilotBot.Mode.BLOCKED, actor.mode());
    }
    @Test void removeWaitsForInflightTickAndNoMutationOccursAfterItReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), stopStarted = new CountDownLatch(1);
        when(effects.contact(body, map)).thenAnswer(inv -> {
            entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return false;
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tick = pool.submit(actor::updateState); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var removal = pool.submit(() -> { stopStarted.countDown(); actor.remove(); });
            assertTrue(stopStarted.await(3, TimeUnit.SECONDS)); assertFalse(removal.isDone());
            release.countDown(); tick.get(3, TimeUnit.SECONDS); removal.get(3, TimeUnit.SECONDS);
            clearInvocations(effects); actor.updateState(); verifyNoInteractions(effects);
        } finally { release.countDown(); }
    }

    @Test void partyDecisionsKeepRootAndOnlyRunWhileObserved() {
        BotSM delegate = mock(BotSM.class);
        when(delegate.getChr()).thenReturn(body);
        assertTrue(actor.beginDuty(HybridPilotBot.Mode.PARTY, 7, delegate));
        assertFalse(actor.beginDuty(HybridPilotBot.Mode.PARTY, 8, delegate));
        actor.updateState();
        verify(delegate).updateState();
        verify(delegate, never()).startScheduledTask();
        verifyNoInteractions(effects);
        assertSame(actor, CharacterStorage.getBotById(body.getId()));
        when(map.getCharacters()).thenReturn(List.of()); actor.updateState();
        verify(delegate, times(1)).updateState();
        assertEquals(HybridPilotBot.Mode.DORMANT, actor.mode());
        actor.endDuty(6); // Stale release cannot end current ownership.
        when(map.getCharacters()).thenReturn(List.of(human)); actor.updateState();
        verify(delegate, times(2)).updateState();
        actor.endDuty(7); now.addAndGet(1500); actor.updateState();
        verify(effects).attack(body, map);
        verify(delegate).setRunning(false);
        verify(body, never()).updateHp(anyInt());
    }
    @Test void offCancelsPartyBeforeRemovingBodyAndLateDelegateCannotRun() {
        BotSM delegate = mock(BotSM.class); when(delegate.getChr()).thenReturn(body);
        assertTrue(actor.beginDuty(HybridPilotBot.Mode.PARTY, 7, delegate));
        actor.remove(); actor.updateState(); actor.endDuty(7);
        var order = inOrder(delegate, effects);
        order.verify(delegate).setRunning(false);
        order.verify(effects).cancelDuty(body);
        order.verify(effects).remove(body);
        verify(delegate, never()).updateState();
        assertFalse(actor.beginDuty(HybridPilotBot.Mode.PARTY, 8, delegate));
    }
    @Test void partyFaultReleasesDutyAndStopsRoot() {
        BotSM delegate = mock(BotSM.class); when(delegate.getChr()).thenReturn(body);
        doThrow(new IllegalStateException("party failure")).when(delegate).updateState();
        assertTrue(actor.beginDuty(HybridPilotBot.Mode.PARTY, 7, delegate));
        actor.updateState(); actor.updateState();
        verify(delegate, times(1)).updateState(); verify(effects).cancelDuty(body);
        assertEquals(HybridPilotBot.Mode.FAULTED, actor.mode());
    }
    @Test void partyChatSharesMailboxWithoutAmbientAttacksOrHealing() {
        BotSM delegate = mock(BotSM.class); when(delegate.getChr()).thenReturn(body);
        assertTrue(actor.beginDuty(HybridPilotBot.Mode.PARTY, 7, delegate));
        assertTrue(actor.chat(human, "Hybrid1 status")); actor.updateState();
        verify(delegate).updateState(); verify(effects).speak(eq(body), contains("PARTY"));
        verify(effects, never()).attack(any(), any()); verify(effects, never()).contact(any(), any());
        verify(body, never()).updateHp(anyInt());
    }
    @Test void failedPartyCleanupIsRetryableBeforeBodyRemoval() {
        BotSM delegate = mock(BotSM.class); when(delegate.getChr()).thenReturn(body);
        assertTrue(actor.beginDuty(HybridPilotBot.Mode.PARTY, 7, delegate));
        doThrow(new IllegalStateException("cleanup")).doNothing().when(effects).cancelDuty(body);
        assertThrows(IllegalStateException.class, actor::remove);
        verify(effects, never()).remove(body);
        actor.updateState(); verify(delegate, never()).updateState();
        actor.remove(); verify(effects, times(2)).cancelDuty(body); verify(effects).remove(body);
        assertTrue(actor.removed());
    }
    @Test void emptyMapPilotCannotReceiveCompanionDamage() {
        when(map.getCharacters()).thenReturn(List.of());
        assertFalse(soloMapling.ArtificialPlayer.CompanionSystem.CompanionRuntime.active(body));
        assertEquals(-1, soloMapling.ArtificialPlayer.CompanionSystem.CompanionIncomingDamage.apply(body, 200, false));
        verify(body, never()).addHP(anyInt());
    }
}

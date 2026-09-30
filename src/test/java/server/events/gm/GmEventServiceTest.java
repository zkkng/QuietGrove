package server.events.gm;

import client.Character;
import client.Client;
import client.command.commands.gm0.EventCommand;
import client.command.commands.gm0.JoinEventCommand;
import client.command.commands.gm3.EndEventCommand;
import client.command.commands.gm3.StartEventCommand;
import client.command.commands.gm3.StartMapEventCommand;
import client.command.commands.gm3.StopMapEventCommand;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.MapId;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;
import java.awt.Point;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs real command -> service -> roster -> OX timer -> reward/cleanup code with local mock I/O. */
class GmEventServiceTest {
    private static final AtomicInteger WORLDS=new AtomicInteger(700);
    private final GmEventService service=GmEventService.getInstance();
    private final Channel channel=mock(Channel.class);
    private final MapleMap arena=mock(MapleMap.class),home=mock(MapleMap.class),winner=mock(MapleMap.class);
    private final Map<Integer,Character> actors=new HashMap<>();
    private final Map<Integer,AtomicReference<MapleMap>> positions=new HashMap<>();
    private final Map<Integer,AtomicReference<Point>> coordinates=new HashMap<>();
    private final AtomicReference<Event> compatibility=new AtomicReference<>();
    private final AtomicReference<OxQuiz> ox=new AtomicReference<>();
    private final AtomicBoolean started=new AtomicBoolean(),oxFlag=new AtomicBoolean();
    private record Callback(long delay,Runnable action) {}
    private final ArrayDeque<Callback> callbacks=new ArrayDeque<>();
    private Runnable watchdog;
    private Character host,first,second;
    private MockedStatic<TimerManager> timer;
    private int world;

    @BeforeEach void prepare() {
        world=WORLDS.incrementAndGet();
        var manager=mock(MapManager.class); var storage=mock(PlayerStorage.class); var portal=mock(Portal.class);
        when(channel.getId()).thenReturn(1); when(channel.getWorld()).thenReturn(world);
        when(channel.getMapFactory()).thenReturn(manager); when(channel.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(anyInt())).thenAnswer(i->actors.get(i.getArgument(0)));
        when(channel.getEvent()).thenAnswer(i->compatibility.get());
        doAnswer(i->{compatibility.set(i.getArgument(0));return null;}).when(channel).setEvent(any());
        when(arena.getId()).thenReturn(MapId.EVENT_OX_QUIZ); when(home.getId()).thenReturn(100000000); when(winner.getId()).thenReturn(MapId.EVENT_WINNER);
        when(arena.getPortal(0)).thenReturn(portal); when(arena.getPortal("join00")).thenReturn(portal);
        when(home.getPortal(0)).thenReturn(portal); when(winner.getPortal(0)).thenReturn(portal);
        when(manager.getMap(anyInt())).thenAnswer(i->{ int id=i.getArgument(0); return id==arena.getId()?arena:id==winner.getId()?winner:home; });
        when(arena.getOx()).thenAnswer(i->ox.get()); doAnswer(i->{ox.set(i.getArgument(0));return null;}).when(arena).setOx(any());
        when(arena.isOxQuiz()).thenAnswer(i->oxFlag.get()); doAnswer(i->{oxFlag.set(i.getArgument(0));return null;}).when(arena).setOxQuiz(anyBoolean());
        when(arena.eventStarted()).thenAnswer(i->started.get()); doAnswer(i->{started.set(true);return null;}).when(arena).startEvent();
        doAnswer(i->{started.set(i.getArgument(0));return null;}).when(arena).setEventStarted(anyBoolean());
        when(arena.getCharacters()).thenAnswer(i->actors.values().stream().filter(a->a.getMap()==arena).toList());
        var timerManager=mock(TimerManager.class); var future=mock(ScheduledFuture.class);
        when(timerManager.schedule(any(Runnable.class),anyLong())).thenAnswer(i->{callbacks.add(new Callback(i.getArgument(1),i.getArgument(0)));return future;});
        when(timerManager.register(any(Runnable.class),anyLong(),anyLong())).thenAnswer(i->{watchdog=i.getArgument(0);return future;});
        timer=mockStatic(TimerManager.class); timer.when(TimerManager::getInstance).thenReturn(timerManager);
        host=actor(999,arena,true); first=actor(1,home,false); second=actor(2,home,false);
    }
    private Character actor(int id,MapleMap map,boolean gm) {
        var actor=mock(Character.class); var client=mock(Client.class);
        actors.put(id,actor); positions.put(id,new AtomicReference<>(map)); coordinates.put(id,new AtomicReference<>(new Point()));
        when(actor.getId()).thenReturn(id); when(actor.getWorld()).thenReturn(world); when(actor.getClient()).thenReturn(client);
        when(actor.getMap()).thenAnswer(i->positions.get(id).get()); when(actor.getMapId()).thenAnswer(i->actor.getMap().getId());
        when(actor.getPosition()).thenAnswer(i->coordinates.get(id).get()); when(actor.isAlive()).thenReturn(true);
        AtomicInteger team=new AtomicInteger();
        when(actor.getTeam()).thenAnswer(i->(byte)team.get());
        doAnswer(i->{team.set(i.getArgument(0));return null;}).when(actor).setTeam(anyInt());
        when(actor.isGM()).thenReturn(gm); when(actor.gmLevel()).thenReturn(gm?3:0);
        when(actor.getWarpMap(anyInt())).thenReturn(home);
        when(client.getPlayer()).thenReturn(actor); when(client.getChannelServer()).thenReturn(channel);
        when(client.getChannel()).thenReturn(1); when(client.getWorld()).thenReturn(world);
        doAnswer(i->{positions.get(id).set(i.getArgument(0));return null;}).when(actor).changeMap(any(MapleMap.class),any(Portal.class));
        doAnswer(i->{positions.get(id).set((int)i.getArgument(0)==MapId.EVENT_WINNER?winner:home);return null;}).when(actor).changeMap(anyInt());
        return actor;
    }
    @AfterEach void cleanup() { service.cancelChannel(channel); timer.close(); }

    @Test void actualCompatibilityCommandsHonorSingleCountCloseEntryAndFiniteOxWinnerClaim() {
        new StartEventCommand().execute(host.getClient(),new String[]{"2"});
        assertNotNull(compatibility.get()); assertEquals(2,compatibility.get().getLimit());
        new JoinEventCommand().execute(first.getClient(),new String[0]);
        assertTrue(service.join(second).startsWith("You joined")); assertEquals(0,compatibility.get().getLimit());
        new EndEventCommand().execute(host.getClient(),new String[0]);
        assertNotNull(compatibility.get()); assertTrue(service.status(channel).contains("entry=false"));
        new StartMapEventCommand().execute(host.getClient(),new String[0]);
        assertTrue(service.status(channel).contains("COUNTDOWN"));
        callbacks.remove().action().run();
        OxQuiz game=ox.get(); assertNotNull(game);
        for(var question:game.selectedQuestions()) {
            for(int id:new int[]{1,2}) coordinates.get(id).set(new Point(question.answer()==0?0:-500,0));
            Callback callback=callbacks.remove(); assertTrue(callback.delay()>0); callback.action().run();
        }
        assertEquals(10,game.completedRounds()); assertNull(compatibility.get()); assertNull(ox.get());
        assertSame(winner,first.getMap()); assertSame(winner,second.getMap());
        verify(host,never()).gainExp(anyInt(),anyBoolean(),anyBoolean());
        try(var inventory=mockStatic(InventoryManipulator.class)) {
            inventory.when(()->InventoryManipulator.addById(first.getClient(),4031019,(short)1)).thenReturn(false,true);
            assertFalse(service.claimReward(first)); assertSame(winner,first.getMap());
            assertTrue(service.claimReward(first)); assertSame(home,first.getMap());
            assertFalse(service.claimReward(first));
            inventory.verify(()->InventoryManipulator.addById(first.getClient(),4031019,(short)1),times(2));
            var outsider=actor(3,winner,false); assertFalse(service.claimReward(outsider));
        }
        assertTrue(service.leave(second).startsWith("You left"));
        assertTrue(service.leave(second).contains("not registered"));
    }
    @Test void cancelledOldQuestionCannotMutateRestartedSameMapAndRestoresMembers() {
        service.create(host,"ox",2); service.join(first); service.start(host);
        callbacks.remove().action().run();
        Callback old=callbacks.remove(); OxQuiz oldGame=ox.get();
        new StopMapEventCommand().execute(host.getClient(),new String[0]);
        assertSame(home,first.getMap()); assertNull(compatibility.get()); assertFalse(started.get());
        service.create(host,"ox",2); service.join(first); service.start(host);
        callbacks.remove().action().run();
        OxQuiz fresh=ox.get(); old.action().run();
        assertSame(fresh,ox.get()); assertEquals(0,fresh.completedRounds()); assertEquals(0,oldGame.completedRounds());
        verify(first,never()).gainExp(anyInt(),anyBoolean(),anyBoolean());
    }
    @Test void hostLogoutCancelsOwnedStateAndUnapprovedCommandsCannotCreateOrReplace() {
        new EventCommand().execute(first.getClient(),new String[]{"create","ox","2"}); assertNull(compatibility.get());
        new EventCommand().execute(first.getClient(),new String[]{"request","ox"}); assertNull(compatibility.get());
        assertTrue(service.create(host,"treasure",50).contains("missing")); assertNull(compatibility.get());
        service.create(host,"ox",2); Event firstEvent=compatibility.get();
        assertTrue(service.create(host,"ox",100).contains("already")); assertSame(firstEvent,compatibility.get());
        service.join(first); actors.remove(host.getId()); watchdog.run();
        assertNull(compatibility.get()); assertSame(home,first.getMap());
    }
    private Map<Integer,MapleMap> install(String key) {
        Map<Integer,MapleMap> fields=new HashMap<>();var definition=EventDefinition.find(key);
        fields.put(home.getId(),home);fields.put(winner.getId(),winner);
        for(int id:definition.mapIds()) {
            var field=mock(MapleMap.class);when(field.getId()).thenReturn(id);when(field.getAllReactors()).thenReturn(java.util.List.of());
            fields.put(id,field);
            Map<String,Portal> portals=new HashMap<>();
            for(String name:new String[]{"start00","join00","join01","in00","st00","st01","ch00"}) {
                Portal p=mock(Portal.class);when(p.getName()).thenReturn(name);when(p.getPosition()).thenReturn(new Point());when(p.getPortalStatus()).thenReturn(true);
                portals.put(name,p);
            }
            int index=definition.mapIds().indexOf(id),next=index+1<definition.mapIds().size()?definition.mapIds().get(index+1):MapId.EVENT_WINNER;
            when(portals.get("in00").getTargetMapId()).thenReturn(next);when(portals.get("in00").getTarget()).thenReturn("start00");
            when(field.getPortal(anyString())).thenAnswer(i->portals.get(i.getArgument(0)));when(field.getPortal(0)).thenReturn(portals.get("start00"));
        }
        Portal winnerStart=winner.getPortal(0);when(winner.getPortal("start00")).thenReturn(winnerStart);
        when(channel.getMapFactory().getMap(anyInt())).thenAnswer(i->fields.get(i.getArgument(0)));
        return fields;
    }
    @Test void fullFitnessPortalSequenceCommitsAfterActualWarpAndSurvivesHostCancel() {
        var fields=install("fitness");assertTrue(service.create(host,"fitness",2).startsWith("Opened"));
        service.join(first);service.join(second);service.start(host);callbacks.remove().action().run();
        service.coursePortal(first,first.getMap().getPortal("join00"));
        // A portal from another exact map cannot skip stage tracking.
        service.coursePortal(first,fields.get(109040004).getPortal("in00"));assertEquals(109040000,first.getMapId());
        for(int id:EventDefinition.find("fitness").mapIds()) {
            assertEquals(id,first.getMapId());service.coursePortal(first,first.getMap().getPortal("in00"));
        }
        assertSame(winner,first.getMap());service.cancel(host);assertSame(winner,first.getMap());assertSame(home,second.getMap());
        try(var inventory=mockStatic(InventoryManipulator.class)) {
            inventory.when(()->InventoryManipulator.addById(first.getClient(),4031019,(short)1)).thenReturn(true);
            assertTrue(service.claimReward(first));assertFalse(service.claimReward(first));
        }
    }
    @Test void failedFitnessFinishWarpCannotCreateEntitlement() {
        install("fitness");service.create(host,"fitness",2);service.join(first);service.start(host);callbacks.remove().action().run();
        service.coursePortal(first,first.getMap().getPortal("join00"));
        for(int id=109040000;id<109040004;id++) service.coursePortal(first,first.getMap().getPortal("in00"));
        doAnswer(i->null).when(first).changeMap(eq(winner),any(Portal.class));
        service.coursePortal(first,first.getMap().getPortal("in00"));assertSame(home,first.getMap());assertFalse(service.claimReward(first));
    }
    @Test void snowballStartsBothTeamsAndTimeoutDrawCannotGrantPrizes() {
        var fields=install("snowball");var snow=fields.get(MapId.EVENT_SNOWBALL);
        AtomicReference<Snowball> lower=new AtomicReference<>(),upper=new AtomicReference<>();
        when(snow.getSnowball(0)).thenAnswer(i->lower.get());when(snow.getSnowball(1)).thenAnswer(i->upper.get());
        doAnswer(i->{((int)i.getArgument(0)==0?lower:upper).set(i.getArgument(1));return null;}).when(snow).setSnowball(anyInt(),any());
        service.create(host,"snowball",2);service.join(first);service.join(second);service.start(host);callbacks.remove().action().run();
        assertSame(snow,first.getMap());assertSame(snow,second.getMap());assertTrue(lower.get().isHittable());assertTrue(upper.get().isHittable());
        try(var melee=mockStatic(EventMelee.class)) {
            melee.when(()->EventMelee.legal(first)).thenReturn(true);coordinates.get(1).set(new Point(400,155));
            assertTrue(service.snowballHit(first,0));assertFalse(service.snowballHit(first,1));
        }
        service.finish(host);
        assertSame(snow,first.getMap());assertSame(snow,second.getMap());
        callbacks.stream().filter(callback -> callback.delay()==10_000).findFirst().orElseThrow().action().run();
        assertSame(home,first.getMap());assertSame(home,second.getMap());assertNull(lower.get());assertNull(upper.get());
        assertFalse(service.claimReward(first));assertFalse(service.snowballHit(first,0));
        assertTrue(service.create(host,"snowball",2).startsWith("Opened"));
    }
    @Test void snowballResultSettlesWhenWinnerDisconnectsBetweenRosterChecks() {
        var fields=install("snowball");var snow=fields.get(MapId.EVENT_SNOWBALL);
        AtomicReference<Snowball> lower=new AtomicReference<>(),upper=new AtomicReference<>();
        when(snow.getSnowball(0)).thenAnswer(i->lower.get());when(snow.getSnowball(1)).thenAnswer(i->upper.get());
        doAnswer(i->{((int)i.getArgument(0)==0?lower:upper).set(i.getArgument(1));return null;}).when(snow).setSnowball(anyInt(),any());
        service.create(host,"snowball",2);service.join(first);service.join(second);service.start(host);callbacks.remove().action().run();
        assertEquals(0,first.getTeam());assertEquals(1,second.getTeam());
        lower.get().position(1);service.finish(host);
        var reads=new AtomicInteger();
        when(channel.getPlayerStorage().getCharacterById(first.getId()))
                .thenAnswer(i->reads.getAndIncrement()==0?first:null);
        Callback result=callbacks.stream().filter(callback->callback.delay()==10_000).findFirst().orElseThrow();
        assertDoesNotThrow(()->result.action().run());
        assertTrue(reads.get()>=2);
        assertSame(home,second.getMap());assertNull(lower.get());assertNull(upper.get());
        assertNull(compatibility.get());assertFalse(service.claimReward(first));
    }
    @Test void staleSavedLocationReturnsThroughOriginalPortalAndOnlyClearsAfterSuccess() {
        Portal saved=mock(Portal.class);when(home.getPortal(7)).thenReturn(saved);
        int homeId=home.getId();when(first.peekSavedLocation("EVENT")).thenReturn(homeId);when(first.peekSavedLocationPortal("EVENT")).thenReturn(7);
        positions.get(1).set(arena);service.reconcileLogin(first);assertSame(home,first.getMap());verify(first).changeMap(home,saved);
        verify(first).clearSavedLocation(server.maps.SavedLocationType.EVENT);
        positions.get(2).set(arena);when(second.peekSavedLocation("EVENT")).thenReturn(homeId);
        doAnswer(i->null).when(second).changeMap(eq(home),any(Portal.class));service.reconcileLogin(second);
        verify(second,never()).clearSavedLocation(server.maps.SavedLocationType.EVENT);
    }
    private record TreasureFixture(MapleMap lith, java.util.List<server.maps.MapItem> drops,EventSession session) {}
    private TreasureFixture startTreasureWithActualOwnedDrops() throws ReflectiveOperationException {
        var fields=install("treasure");var field=fields.get(109010100);
        var lith=mock(MapleMap.class);when(lith.getId()).thenReturn(104000000);fields.put(104000000,lith);
        Portal landing=home.getPortal(0);when(lith.getPortal(0)).thenReturn(landing);
        for(Character actor:new Character[]{first,second}) {
            var inventory=mock(client.inventory.Inventory.class);
            when(inventory.list()).thenReturn(java.util.List.of());when(actor.getInventory(client.inventory.InventoryType.ETC)).thenReturn(inventory);
            when(actor.canHold(4031019)).thenReturn(true);
            doAnswer(i->{positions.get(actor.getId()).set(fields.get((int)i.getArgument(0)));return null;}).when(actor).changeMap(anyInt());
        }
        var chests=new java.util.ArrayList<server.maps.Reactor>();var drops=new java.util.ArrayList<server.maps.MapItem>();
        for(int id=1;id<=12;id++) {
            var chest=mock(server.maps.Reactor.class);chests.add(chest);
            when(chest.getId()).thenReturn(9002002);when(chest.getObjectId()).thenReturn(id);when(chest.getMap()).thenReturn(field);
            when(chest.isAlive()).thenReturn(true);when(chest.getPosition()).thenReturn(new Point());
            when(chest.getState()).thenReturn((byte)4);when(chest.isRecentHitFromAttack()).thenReturn(true);when(field.getReactorByOid(id)).thenReturn(chest);
        }
        when(field.getAllReactors()).thenReturn(chests);
        when(field.spawnItemDropNoExpire(any(),eq(first),any(),any(),eq(true),eq(false))).thenAnswer(i->{
            var drop=mock(server.maps.MapItem.class);drops.add(drop);int oid=100+drops.size();
            when(drop.getObjectId()).thenReturn(oid);when(drop.getItem()).thenReturn(i.getArgument(2));when(drop.getPosition()).thenReturn(new Point());
            when(field.getMapObject(oid)).thenReturn(drop);var picked=new AtomicBoolean();when(drop.isPickedUp()).thenAnswer(call->picked.get());
            doAnswer(call->{picked.set(true);return null;}).when(field).pickItemDrop(any(),eq(drop));return drop;
        });
        assertTrue(service.create(host,"treasure",2).startsWith("Opened"));service.join(first);service.join(second);service.start(host);callbacks.remove().action().run();
        assertTrue(service.treasureField(first,109010100).startsWith("Search"));
        var currentField=GmEventService.class.getDeclaredField("current");currentField.setAccessible(true);
        var live=((Map<?,?>)currentField.get(service)).values().iterator().next();
        var sessionField=live.getClass().getDeclaredField("session");sessionField.setAccessible(true);var session=(EventSession)sessionField.get(live);
        var treasureField=live.getClass().getDeclaredField("treasure");treasureField.setAccessible(true);var game=(TreasureGame)treasureField.get(live);
        var randomField=TreasureGame.class.getDeclaredField("random");randomField.setAccessible(true);((java.util.Random)randomField.get(game)).setSeed(2);
        for(var chest:chests) service.treasureChestBroken(first,chest);
        assertFalse(drops.isEmpty());return new TreasureFixture(lith,drops,session);
    }
    @Test void treasureTimeoutKeepsCollectedEligibilityAndVikinInventoryRetryExactlyOnce() throws ReflectiveOperationException {
        var fixture=startTreasureWithActualOwnedDrops();var scroll=fixture.drops().getFirst().getItem();
        try(var inventory=mockStatic(InventoryManipulator.class)) {
            inventory.when(()->InventoryManipulator.addFromDrop(first.getClient(),scroll,true)).thenReturn(false,true);
            assertTrue(service.treasurePickup(first,fixture.drops().getFirst(),-1));
            assertTrue(service.treasurePickup(first,fixture.drops().getFirst(),-1));
            var deadline=EventSession.class.getDeclaredField("deadlineMs");deadline.setAccessible(true);deadline.setLong(fixture.session(),System.currentTimeMillis()-1);
            callbacks.remove().action().run();
            assertSame(fixture.lith(),first.getMap());assertSame(home,second.getMap());
            assertFalse(service.claimReward(second));
            inventory.when(()->InventoryManipulator.addById(first.getClient(),4031019,(short)1)).thenReturn(false,true);
            assertFalse(service.claimReward(first));assertSame(fixture.lith(),first.getMap());
            assertTrue(service.claimReward(first));assertSame(home,first.getMap());assertFalse(service.claimReward(first));
            inventory.verify(()->InventoryManipulator.addById(first.getClient(),4031019,(short)1),times(2));
        }
    }
    @Test void failedTreasureRedemptionWarpKeepsActiveEligibilityForRetry() throws ReflectiveOperationException {
        var fixture=startTreasureWithActualOwnedDrops();var scroll=fixture.drops().getFirst().getItem();
        try(var inventory=mockStatic(InventoryManipulator.class)) {
            inventory.when(()->InventoryManipulator.addFromDrop(first.getClient(),scroll,true)).thenReturn(true);
            service.treasurePickup(first,fixture.drops().getFirst(),-1);
            doAnswer(i->null).when(first).changeMap(eq(fixture.lith()),any(Portal.class));
            assertFalse(service.redeemTreasure(first));assertTrue(fixture.session().active(first.getId()));
            inventory.verify(()->InventoryManipulator.addById(first.getClient(),4031019,(short)1),never());
            doAnswer(i->{positions.get(1).set(i.getArgument(0));return null;}).when(first).changeMap(eq(fixture.lith()),any(Portal.class));
            inventory.when(()->InventoryManipulator.addById(first.getClient(),4031019,(short)1)).thenReturn(true);
            assertTrue(service.redeemTreasure(first));assertSame(home,first.getMap());assertFalse(service.redeemTreasure(first));
        }
    }
}

package server.events.gm;
import client.Character;
import client.Client;
import client.inventory.*;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import server.maps.*;
import java.awt.Point;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TreasureGameTest {
    @Test void cleanupRemovesEveryTaggedScrollFromRealLiveInventoryView() {
        MapleMap map=mock(MapleMap.class);Reactor chest=mock(Reactor.class);
        when(map.getAllReactors()).thenReturn(List.of(chest));
        when(chest.getId()).thenReturn(9002002);when(chest.isAlive()).thenReturn(true);
        UUID eventId=new UUID(1,2);
        var game=new TreasureGame(List.of(map),eventId,()->true,id->true);
        String tag="EV"+eventId.toString().replace("-","").substring(0,11);
        Character actor=mock(Character.class);Client client=mock(Client.class);
        Inventory inventory=new Inventory(actor,InventoryType.ETC,(byte)10);
        when(actor.getClient()).thenReturn(client);when(client.getPlayer()).thenReturn(actor);
        when(actor.getInventory(InventoryType.ETC)).thenReturn(inventory);
        Item first=new Item(TreasureGame.SCROLL,(short)1,(short)1);
        Item second=new Item(TreasureGame.SCROLL,(short)2,(short)1);
        Item unrelated=new Item(TreasureGame.SCROLL,(short)3,(short)1);
        first.setOwner(tag);second.setOwner(tag);unrelated.setOwner("other-event");
        inventory.addItemFromDB(first);inventory.addItemFromDB(second);inventory.addItemFromDB(unrelated);
        assertDoesNotThrow(()->game.removeScrolls(actor));
        assertNull(inventory.getItem((short)1));
        assertNull(inventory.getItem((short)2));
        assertSame(unrelated,inventory.getItem((short)3));
    }
    @Test void finiteChestDropsPickupRetryAndCleanupPreserveUnrelatedObjects() {
        MapleMap map=mock(MapleMap.class);Character actor=mock(Character.class);Client client=mock(Client.class);
        var inventory=mock(Inventory.class);when(actor.getInventory(InventoryType.ETC)).thenReturn(inventory);when(inventory.list()).thenReturn(List.of());
        when(actor.getId()).thenReturn(1);when(actor.getMap()).thenReturn(map);when(actor.isAlive()).thenReturn(true);
        when(actor.getClient()).thenReturn(client);when(actor.getPosition()).thenReturn(new Point(0,0));
        List<Reactor> chests=new ArrayList<>();List<MapItem> drops=new ArrayList<>();
        for(int id=1;id<=12;id++) {
            Reactor chest=mock(Reactor.class);chests.add(chest);when(chest.getId()).thenReturn(9002002);
            when(chest.getObjectId()).thenReturn(id);when(chest.isAlive()).thenReturn(true);when(chest.getMap()).thenReturn(map);
            when(chest.getPosition()).thenReturn(new Point());when(chest.getDelay()).thenReturn(270000);
            when(chest.getState()).thenReturn((byte)4);when(chest.isRecentHitFromAttack()).thenReturn(true);
            when(map.getReactorByOid(id)).thenReturn(chest);
        }
        when(map.getAllReactors()).thenReturn(chests);
        when(map.spawnItemDropNoExpire(any(),eq(actor),any(),any(),eq(true),eq(false))).thenAnswer(call->{
            MapItem drop=mock(MapItem.class);drops.add(drop);when(drop.getObjectId()).thenReturn(100+drops.size());
            when(drop.getItem()).thenReturn(call.getArgument(2));when(drop.getPosition()).thenReturn(new Point());
            when(map.getMapObject(drop.getObjectId())).thenReturn(drop);return drop;
        });
        AtomicBoolean current=new AtomicBoolean(true);
        var game=new TreasureGame(List.of(map),new UUID(1,2),current::get,id->id==1);
        try(var melee=mockStatic(EventMelee.class);var items=mockStatic(InventoryManipulator.class)) {
            melee.when(()->EventMelee.legal(actor)).thenReturn(true);game.start();assertEquals(12,game.chestCount());
            assertFalse(game.permitHit(actor,chests.getFirst(),1001004,1000));
            assertTrue(game.permitHit(actor,chests.getFirst(),0,1000));assertFalse(game.permitHit(actor,chests.get(1),0,1001));
            long now=2000;
            for(Reactor chest:chests) {assertTrue(game.permitHit(actor,chest,0,now));game.broken(actor,chest);game.broken(actor,chest);now+=1000;}
            assertTrue(drops.size()>0 && drops.size()<=12);MapItem first=drops.getFirst();
            assertEquals(4031018,first.getItem().getItemId());assertTrue(first.getItem().getOwner().startsWith("EV"));
            items.when(()->InventoryManipulator.addFromDrop(client,first.getItem(),true)).thenReturn(false,true);
            assertTrue(game.pickup(actor,first,-1,20000));assertFalse(game.eligible(1));verify(map,never()).pickItemDrop(any(),eq(first));
            doAnswer(call->{when(first.isPickedUp()).thenReturn(true);return null;}).when(map).pickItemDrop(any(),eq(first));
            assertTrue(game.pickup(actor,first,0,20001));assertTrue(game.eligible(1));assertTrue(game.pickup(actor,first,0,20002));
            items.verify(()->InventoryManipulator.addFromDrop(client,first.getItem(),true),times(2));
            MapItem unrelated=mock(MapItem.class);assertFalse(game.owns(unrelated));assertFalse(game.pickup(actor,unrelated,-1,20003));
            game.dispose();game.dispose();assertFalse(game.permitHit(actor,chests.get(1),0,50000));
            for(Reactor chest:chests) verify(chest).setDelay(270000);
            verify(map,never()).pickItemDrop(any(),eq(unrelated));
        }
    }
    @Test void pendingRespawnPreventsPreparationAndStaleCallbacksNeverCreateLoot() {
        MapleMap map=mock(MapleMap.class);Reactor chest=mock(Reactor.class);when(chest.getId()).thenReturn(9002002);
        when(map.getAllReactors()).thenReturn(List.of(chest));when(chest.inDelayedRespawn()).thenReturn(true);
        assertThrows(IllegalStateException.class,()->new TreasureGame(List.of(map),UUID.randomUUID(),()->true,id->true));
        when(chest.inDelayedRespawn()).thenReturn(false);when(chest.isAlive()).thenReturn(true);when(chest.getMap()).thenReturn(map);
        when(map.getReactorByOid(chest.getObjectId())).thenReturn(chest);
        AtomicBoolean valid=new AtomicBoolean(true);var game=new TreasureGame(List.of(map),UUID.randomUUID(),valid::get,id->true);game.start();
        valid.set(false);game.broken(mock(Character.class),chest);verify(map,never()).spawnItemDropNoExpire(any(),any(),any(),any(),anyBoolean(),anyBoolean());game.dispose();
    }
    @Test void cancellationWaitsForChestInitializationBeforeRestoringOriginalState() throws Exception {
        MapleMap map=mock(MapleMap.class); Reactor chest=mock(Reactor.class);
        when(map.getAllReactors()).thenReturn(List.of(chest));when(chest.getId()).thenReturn(9002002);
        when(chest.isAlive()).thenReturn(true);when(chest.getDelay()).thenReturn(270000);
        when(chest.getMap()).thenReturn(map);when(map.getReactorByOid(chest.getObjectId())).thenReturn(chest);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),disposing=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;})
                .when(chest).forceHitReactor((byte)0);
        var game=new TreasureGame(List.of(map),UUID.randomUUID(),()->true,id->true);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var start=workers.submit(game::start);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var cancel=workers.submit(()->{disposing.countDown();game.dispose();});
            assertTrue(disposing.await(5,TimeUnit.SECONDS));
            release.countDown();start.get(5,TimeUnit.SECONDS);cancel.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();}
        var order=inOrder(chest);
        order.verify(chest).setDelay(0);
        order.verify(chest).setDelay(270000);
        game.start();verify(chest,times(1)).setDelay(0);
    }
}

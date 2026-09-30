package server.events.gm;

import client.Character;
import client.inventory.Item;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import server.maps.*;
import tools.PacketCreator;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Finite WZ chests, real scroll drops and session-bound redemption. No unrelated drops are touched. */
public final class TreasureGame {
    public static final int SCROLL=4031018;
    private record Chest(Reactor reactor, byte state, byte eventState, int delay, boolean alive) {}
    private record Drop(MapleMap map, MapItem item) {}
    private final Map<Reactor,Chest> chests=new IdentityHashMap<>();
    private final Set<Reactor> broken=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<MapItem,Drop> drops=new IdentityHashMap<>();
    private final Map<Integer,Integer> collected=new HashMap<>();
    private final Map<Integer,Long> ready=new HashMap<>();
    private final BooleanSupplier valid;
    private final IntPredicate participant;
    private final Random random;
    private final String tag;
    private boolean running, disposed;
    private final java.util.concurrent.locks.ReentrantReadWriteLock lifecycle=new java.util.concurrent.locks.ReentrantReadWriteLock(true);

    public TreasureGame(Collection<MapleMap> fields, UUID eventId, BooleanSupplier valid, IntPredicate participant) {
        this.valid=valid; this.participant=participant;
        random=new Random(eventId.getLeastSignificantBits());
        tag="EV"+eventId.toString().replace("-", "").substring(0,11);
        for(MapleMap field:fields) for(Reactor r:field.getAllReactors()) if(r.getId()==9002002) {
            if(r.inDelayedRespawn() || !r.isAlive()) throw new IllegalStateException("A treasure chest is awaiting respawn");
            chests.put(r,new Chest(r,r.getState(),r.getEventState(),r.getDelay(),r.isAlive()));
        }
        if(chests.isEmpty()) throw new IllegalStateException("Treasure chest reactors are missing");
    }
    public void start() {
        lifecycle.writeLock().lock();
        try {
            synchronized(this) { if(disposed || running) return; }
            if(!valid.getAsBoolean()) return;
            for(Chest chest:chests.values()) {
                Reactor r=chest.reactor(); r.lockReactor();
                try {
                    if(!valid.getAsBoolean()) return;
                    r.setDelay(0); r.setEventState((byte)0); r.forceHitReactor((byte)0);
                }
                finally { r.unlockReactor(); }
            }
            synchronized(this) {if(!disposed && valid.getAsBoolean()) running=true;}
        }
        finally {lifecycle.writeLock().unlock();}
    }
    /** Shared human/bot basic attack admission. Canonical reactor progression follows this gate. */
    public boolean permitHit(Character actor, Reactor reactor, int skill, long now) {
        if(!valid.getAsBoolean() || !participant.test(actor.getId())) return false;
        synchronized(this) {
        if(!running || disposed
                || actor.getMap()!=reactor.getMap() || !actor.isAlive() || actor.isChangingMaps()
                || skill!=0 || !EventMelee.legal(actor) || !chests.containsKey(reactor) || broken.contains(reactor)
                || reactor.getMap().getReactorByOid(reactor.getObjectId())!=reactor || !reactor.isAlive()
                || Math.abs(actor.getPosition().x-reactor.getPosition().x)>180
                || Math.abs(actor.getPosition().y-reactor.getPosition().y)>150 || now<ready.getOrDefault(actor.getId(),0L)) return false;
        ready.put(actor.getId(),now+600); return true;
        }
    }
    /** Only the final WZ state invokes this through reactor/9002002.js. */
    public void broken(Character actor, Reactor reactor) {
        lifecycle.readLock().lock();
        try {
        if(!valid.getAsBoolean() || !participant.test(actor.getId()) || reactor.getState()!=4 || !reactor.isRecentHitFromAttack()) return;
        synchronized(this) {
            if(!running || disposed
                    || !chests.containsKey(reactor) || !broken.add(reactor)) return;
            // Restored local profile: one finite scroll on one third of chests; no guessed historical loot table.
            if(random.nextInt(3)!=0) {reactor.setAlive(false);reactor.cancelReactorTimeout();return;}
        }
        reactor.setAlive(false); reactor.cancelReactorTimeout();
        if(reactor.getMap().getDroppedItemCount()+1>=YamlConfig.config.server.ITEM_LIMIT_ON_MAP) return;
        Item scroll=new Item(SCROLL,(short)0,(short)1); scroll.setOwner(tag);
        MapItem drop=reactor.getMap().spawnItemDropNoExpire(reactor,actor,scroll,reactor.getPosition(),true,false);
        if(drop!=null) synchronized(this) {drops.put(drop,new Drop(reactor.getMap(),drop));}
        } finally {lifecycle.readLock().unlock();}
    }
    public synchronized boolean owns(MapItem item) { return drops.containsKey(item); }
    /** Covers player, pet and bot pickups through Character.pickupItem. */
    public boolean pickup(Character actor,MapItem item,int petIndex,long now) {
        synchronized(actor) {
            lifecycle.readLock().lock();
            try {
                Drop drop;
                synchronized(this) {drop=drops.get(item);}
                if(drop==null) return false;
                item.lockItem();
                try {
                    synchronized(this) {if(!running || disposed) return true;}
                    if(!valid.getAsBoolean() || !participant.test(actor.getId())
                            || !actor.isAlive() || actor.isChangingMaps() || actor.getMap()!=drop.map()
                            || drop.map().getMapObject(item.getObjectId())!=item || item.isPickedUp()
                            || now-item.getDropTime()<400 || actor.getPosition().distanceSq(item.getPosition())>200*200) return true;
                    if(!InventoryManipulator.addFromDrop(actor.getClient(),item.getItem(),true)) return true;
                    synchronized(this) {collected.merge(actor.getId(),1,Integer::sum);}
                    drop.map().pickItemDrop(PacketCreator.removeItemFromMap(item.getObjectId(),petIndex>=0?5:2,
                            actor.getId(),petIndex>=0,petIndex),item);
                    return true;
                } finally { item.unlockItem(); actor.sendPacket(PacketCreator.enableActions()); }
            } finally {lifecycle.readLock().unlock();}
        }
    }
    public synchronized boolean eligible(int actorId) { return collected.getOrDefault(actorId,0)>0; }
    public synchronized Set<Integer> eligibleActors() { return Set.copyOf(collected.keySet()); }
    /** Stop intake and wait for already accepted pickups before the service commits timeout results. */
    public Set<Integer> closeAndEligible() {
        lifecycle.writeLock().lock();
        try {synchronized(this) {running=false;return Set.copyOf(collected.keySet());}}
        finally {lifecycle.writeLock().unlock();}
    }
    public void removeScrolls(Character actor) {
        synchronized(actor) {
            for(Item item:List.copyOf(actor.getInventory(InventoryType.ETC).list()))
                if(item.getItemId()==SCROLL && tag.equals(item.getOwner()))
                InventoryManipulator.removeFromSlot(actor.getClient(),InventoryType.ETC,item.getPosition(),item.getQuantity(),false);
            synchronized(this) { collected.remove(actor.getId()); ready.remove(actor.getId()); }
        }
    }
    public void dispose() {
        List<Drop> remaining;
        lifecycle.writeLock().lock();
        try {synchronized(this) {if(disposed) return;disposed=true;running=false;remaining=List.copyOf(drops.values());}}
        finally {lifecycle.writeLock().unlock();}
        for(Drop drop:remaining) {
            MapItem item=drop.item(); item.lockItem();
            try { if(!item.isPickedUp() && drop.map().getMapObject(item.getObjectId())==item)
                drop.map().pickItemDrop(PacketCreator.removeItemFromMap(item.getObjectId(),0,0),item);
            } finally { item.unlockItem(); }
        }
        for(Chest chest:chests.values()) {
            Reactor r=chest.reactor(); r.lockReactor();
            try {
                // Event delay is zero, so no event respawn is scheduled; restore this exact object only.
                r.cancelReactorTimeout(); r.setState(chest.state()); r.setEventState(chest.eventState());
                r.setDelay(chest.delay()); r.setAlive(chest.alive());
                if(r.getMap().getReactorByOid(r.getObjectId())==r) r.getMap().broadcastMessage(r.makeSpawnData());
            } finally { r.unlockReactor(); }
        }
    }
    public synchronized int chestCount() { return chests.size(); }
}

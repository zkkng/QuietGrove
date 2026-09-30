package server.trainer;

import client.Character;
import client.inventory.Inventory;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.inventory.ModifyInventory;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapItem;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.awt.Point;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** One database commit owns both the finite asset and its recipient inventory. */
public final class TrainerVenuePickup {
    private static final Logger log = LoggerFactory.getLogger(TrainerVenuePickup.class);
    private static final Set<Character> unresolved = Collections.newSetFromMap(new WeakHashMap<>());
    private TrainerVenuePickup() { }

    public static boolean hasUnresolved(Character actor) {
        synchronized (unresolved) { return unresolved.contains(actor); }
    }

    public static void quarantine(Character actor) {
        synchronized (unresolved) { unresolved.add(actor); }
        // A new login constructs a fresh Character from the authoritative inventory.
        actor.getClient().disconnectSession();
    }

    public static boolean pickup(Character actor, MapItem drop, int petIndex, boolean trainerRemote) {
        if (actor == null || drop == null || drop.getVenueAssetId() == null
                || actor.getClient() == null || actor.getClient().getPlayer() != actor
                || !actor.isLoggedinWorld() || !actor.isAlive() || hasUnresolved(actor)) return false;
        MapleMap map = actor.getMap();
        if (map == null || map.getMapObject(drop.getObjectId()) != drop) return false;
        Point from = actor.getPosition();
        if (petIndex >= 0) {
            var pet = actor.getPet(petIndex);
            if (pet == null || !pet.isSummoned() || !actor.isEquippedItemPouch()) return false;
            from = pet.getPos();
        }
        if (from == null || (!trainerRemote && from.distanceSq(drop.getPosition()) > 180L * 180L)) return false;
        if (trainerRemote && !TrainerService.getInstance().venueRemotePickupAllowed(actor)) return false;
        drop.lockItem();
        try {
            long now = System.currentTimeMillis();
            if (drop.isPickedUp() || drop.pickupExpired(now) || now - drop.getDropTime() < TrainerService.getInstance().minimumPickupAge(actor,drop)
                    || !drop.canBePickedBy(actor) || actor.getMap() != map) return false;
            synchronized (actor) {
                Inventory inventory = actor.getInventory(drop.getItem().getInventoryType());
                inventory.lockInventory();
                try {
                    short slot = inventory.getNextFreeSlot();
                    if (slot < 1 || !InventoryManipulator.checkSpace(actor.getClient(), drop.getItemId(),
                            1, drop.getItem().getOwner())) {
                        actor.sendPacket(PacketCreator.getInventoryFull());
                        actor.sendPacket(PacketCreator.getShowInventoryFull());
                        return false;
                    }
                    Item received = drop.getItem().copy();
                    received.setPosition(slot);
                    try (Connection connection = DatabaseConnection.getConnection()) {
                        connection.setAutoCommit(false);
                        try {
                            var asset = TrainerVenueLedger.lockExposedAsset(connection,
                                    drop.getVenueRoundId(), drop.getVenueAssetId(), map.getId(),
                                    map.getChannelServer().getId());
                            if (asset == null) { connection.rollback(); return false; }
                            if (asset.item().getItemId() != drop.getItemId()
                                    || asset.item().getInventoryType() != inventory.getType()
                                    || actor.getMap() != map || drop.pickupExpired(System.currentTimeMillis())) {
                                connection.rollback(); return false;
                            }
                            received = asset.item();
                            received.setPosition(slot);
                            List<Item> snapshot = new ArrayList<>();
                            for (Item item : inventory.list()) snapshot.add(item.copy());
                            snapshot.add(received);
                            ItemFactory.INVENTORY.saveInventoryType(snapshot, inventory.getType(), actor.getId(), connection);
                            TrainerVenueLedger.claimToHumanInventory(connection, drop.getVenueRoundId(),
                                    drop.getVenueAssetId(), actor.getId());
                            if (!TrainerVenueLedger.isPaidRound(connection, drop.getVenueRoundId())) {
                                Character owner = map.getCharacterById(drop.getOwnerId());
                                boolean saw = witnessed(owner, actor, drop.getPosition());
                                boolean remote = petIndex < 0 && actor.getPosition().distanceSq(drop.getPosition()) > 180L * 180L;
                                TrainerSocialMemory.committedLoss(connection, asset.id(), asset.ownerBotName(),
                                        saw ? actor.getId() : null, received.getItemId(), remote, asset.estimatedValue() >= 10_000_000L);
                            }
                            connection.commit();
                        } catch (SQLException | RuntimeException failure) {
                            connection.rollback();
                            throw failure;
                        }
                    } catch (SQLException | RuntimeException failure) {
                        log.error("Venue pickup needs settlement audit round={} asset={} actor={}",
                                drop.getVenueRoundId(), drop.getVenueAssetId(), actor.getId(), failure);
                        TrainerVenueService.fault(map, "Prize settlement needs an audit");
                        try {
                            if (!TrainerVenueLedger.humanClaimCommitted(drop.getVenueRoundId(),
                                    drop.getVenueAssetId(), actor.getId())) return false;
                            // Commit succeeded, only its acknowledgement failed. Project exactly once.
                        } catch (SQLException | RuntimeException unknown) {
                            log.error("Venue claim outcome unknown; disconnecting without stale inventory save actor={}", actor.getId(), unknown);
                            quarantine(actor);
                            return false;
                        }
                    }
                    // The durable copy already exists. Autosave cannot pass the Character monitor
                    // until the in-memory projection contains the same item.
                    try { inventory.addItemFromDB(received); }
                    catch (RuntimeException projectionFailure) {
                        log.error("Committed venue prize could not project into memory actor={} asset={}",
                                actor.getId(), drop.getVenueAssetId(), projectionFailure);
                        TrainerVenueService.fault(map, "Committed prize needs inventory reload");
                        quarantine(actor);
                        return false;
                    }
                    drop.markCollectedBy(actor);
                    map.pickItemDrop(PacketCreator.removeItemFromMap(drop.getObjectId(),
                            petIndex >= 0 ? 5 : 2, actor.getId(), petIndex >= 0, petIndex), drop);
                    actor.sendPacket(PacketCreator.modifyInventory(true,
                            List.of(new ModifyInventory(0, received))));
                    actor.sendPacket(PacketCreator.getShowItemGain(received.getItemId(), received.getQuantity()));
                    return true;
                } finally { inventory.unlockInventory(); }
            }
        } finally {
            drop.unlockItem();
            actor.sendPacket(PacketCreator.enableActions());
        }
    }

    static boolean witnessed(Character owner, Character actor, Point drop) {
        if (owner == null || actor == null || actor.isHidden() || drop == null || owner.getPosition() == null
                || actor.getPosition() == null || owner.getMap() != actor.getMap()) return false;
        Point eye = owner.getPosition(), at = actor.getPosition();
        return Math.abs((long) eye.x - drop.x) <= 900 && Math.abs((long) eye.y - drop.y) <= 600
                && Math.abs((long) eye.x - at.x) <= 900 && Math.abs((long) eye.y - at.y) <= 600;
    }
}

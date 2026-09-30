package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.inventory.*;
import constants.inventory.ItemConstants;
import server.maps.*;
import server.life.Monster;
import tools.PacketCreator;
import java.util.*;

/** Real ownership checks, finite inventory and a temporary player-first preference after a hunt. */
public final class BossLoot {
    private record Preference(String choice, int party, long expires) {}
    private static final Map<Long,Preference> preferences = new HashMap<>();
    private BossLoot() {}
    public static synchronized void preference(long root, String choice, int party) {
        purge(); preferences.put(root,new Preference(choice,party,System.currentTimeMillis()+60_000));
    }
    public static synchronized boolean allowed(Character bot, MapItem drop) {
        if (!(drop.getDropper() instanceof Monster monster)) return true;
        purge(); Preference p = preferences.get(monster.getEncounterId());
        return p == null || p.choice().equals("shared") && bot.getPartyId() == p.party();
    }
    public static boolean pickup(Character bot, MapItem drop) {
        if (bot.getMap() == null || drop == null || !bot.isAlive() || bot.getPosition().distanceSq(drop.getPosition()) > 50*50
                || !allowed(bot,drop)) return false;
        drop.lockItem();
        try {
            if (drop.isPickedUp() || bot.getMap().getMapObject(drop.getObjectId()) != drop || !drop.canBePickedBy(bot)) return false;
            if (drop.getMeso() > 0) {
                if ((long)bot.getMeso()+drop.getMeso() > Integer.MAX_VALUE) return false;
                bot.gainMeso(drop.getMeso(),false);
            }
            else {
                Item item = drop.getItem();
                if (item == null || !bot.needQuestItem(drop.getQuest(),item.getItemId())) return false;
                var ii = server.ItemInformationProvider.getInstance();
                if (ii.isPickupRestricted(item.getItemId()) && bot.haveItem(item.getItemId())) return false;
                Inventory inventory = bot.getInventory(ItemConstants.getInventoryType(item.getItemId()));
                inventory.lockInventory();
                try { if (!store(bot,inventory,item,ii)) return false; }
                finally { inventory.unlockInventory(); }
            }
            bot.getMap().pickItemDrop(PacketCreator.removeItemFromMap(drop.getObjectId(),2,bot.getId()),drop);
            return true;
        } finally { drop.unlockItem(); }
    }
    private static boolean store(Character bot, Inventory inventory, Item item, server.ItemInformationProvider ii) {
        if (item instanceof Equip || ItemConstants.isRechargeable(item.getItemId())) return inventory.addItem(item.copy()) >= 0;
        int maximum = ii.getSlotMaxForCharacter(bot,item.getItemId()), remaining = item.getQuantity();
        if (maximum <= 0 || remaining <= 0) return false;
        var stacks = inventory.listById(item.getItemId()).stream().filter(i -> i.getQuantity() < maximum
                && i.getFlag() == item.getFlag() && i.getOwner().equals(item.getOwner()) && i.getExpiration() == item.getExpiration()).toList();
        int capacity = inventory.getNumFreeSlot()*maximum;
        for (Item stack : stacks) capacity += maximum-stack.getQuantity();
        if (capacity < remaining) return false; // Preflight under the inventory lock: no partial reward on failure.
        for (Item stack : stacks) {
            int amount = Math.min(remaining,maximum-stack.getQuantity());
            stack.setQuantity((short)(stack.getQuantity()+amount)); remaining -= amount;
            if (remaining == 0) return true;
        }
        while (remaining > 0) {
            Item copy = item.copy(); int amount = Math.min(remaining,maximum); copy.setQuantity((short)amount);
            if (inventory.addItem(copy) < 0) throw new IllegalStateException("Inventory capacity changed while locked");
            remaining -= amount;
        }
        return true;
    }
    private static void purge() { long now = System.currentTimeMillis(); preferences.values().removeIf(p -> now >= p.expires()); }
}

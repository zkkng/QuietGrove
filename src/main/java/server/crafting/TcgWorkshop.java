package server.crafting;

import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.crafting.TcgCatalog.Ingredient;
import server.crafting.TcgCatalog.Offer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public final class TcgWorkshop {
    private TcgWorkshop() {}

    public static String exchange(Client client, int npc, int index) {
        List<Offer> offers = TcgCatalog.offersForNpc(npc, client.getPlayer().getMapId());
        if (index < 0 || index >= offers.size()) return "Please choose an item from the menu.";
        if (!client.tryacquireClient()) return "Please finish your other action and try again.";
        List<Inventory> locked = new ArrayList<>();
        try {
            // Share the character-save monitor so meso and inventory snapshots cannot split this exchange.
            synchronized (client.getPlayer()) {
                Offer offer = offers.get(index);
                var types = new TreeSet<InventoryType>(Comparator.comparingInt(InventoryType::getType));
                // Include equipped items for the one-of-a-kind check; never consume them.
                types.add(InventoryType.EQUIPPED);
                types.add(ItemConstants.getInventoryType(offer.itemId()));
                for (Ingredient ingredient : offer.ingredients()) types.add(ItemConstants.getInventoryType(ingredient.itemId()));
                for (InventoryType type : types) {
                    Inventory inventory = client.getPlayer().getInventory(type);
                    inventory.lockInventory();
                    locked.add(inventory);
                }
                return TcgExchange.execute(offer, new PlayerInventory(client));
            }
        } finally {
            for (int i = locked.size() - 1; i >= 0; i--) locked.get(i).unlockInventory();
            client.releaseClient();
        }
    }

    private record PlayerInventory(Client client) implements TcgExchange.Inventory {
        private Inventory inventory(int item) {
            return client.getPlayer().getInventory(ItemConstants.getInventoryType(item));
        }

        @Override public int activePetLevel() {
            int level = 0;
            for (Pet pet : client.getPlayer().getPets()) {
                if (pet != null && pet.isSummoned()) level = Math.max(level, pet.getLevel());
            }
            return level;
        }

        @Override public boolean has(Ingredient ingredient) {
            Inventory inventory = inventory(ingredient.itemId());
            return (ingredient.wholeStack() ? inventory.listById(ingredient.itemId()).size()
                    : inventory.countById(ingredient.itemId())) >= ingredient.quantity();
        }

        @Override public boolean canReceive(Offer offer) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            if (ii.getItemData(offer.itemId()) == null || inventory(offer.itemId()).isFull()) return false;
            // A reserved free slot also accommodates different owner/flag stacks without partial insertion.
            if (offer.quantity() > ii.getSlotMax(client, offer.itemId())) return false;
            return !ii.isPickupRestricted(offer.itemId()) || !client.getPlayer().haveItemWithId(offer.itemId(), true);
        }

        @Override public boolean spend(int mesos) {
            return client.getPlayer().trySpendMeso(mesos);
        }

        @Override public void refund(int mesos) {
            client.getPlayer().gainMeso(mesos, false);
        }

        @Override public boolean give(Offer offer) {
            int petId = -1;
            if (ItemConstants.isPet(offer.itemId())) {
                petId = Pet.createPet(offer.itemId());
                if (petId < 0) return false;
            }
            boolean added = false;
            try {
                Item item = ItemConstants.isEquipment(offer.itemId())
                        ? ItemInformationProvider.getInstance().getEquipById(offer.itemId())
                        : new Item(offer.itemId(), (short) 0, (short) offer.quantity(), petId);
                if (item == null) return false;
                if (offer.petDays() > 0) item.setExpiration(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(offer.petDays()));
                // Fixed WZ stats: buying/crafting cannot reroll enhanced-crafting bonuses.
                added = InventoryManipulator.addFromDrop(client, item, true, petId);
                return added;
            } finally {
                if (!added && petId >= 0) Pet.deleteFromDb(client.getPlayer(), petId);
            }
        }

        @Override public void take(Ingredient ingredient) {
            if (ingredient.wholeStack()) {
                List<Item> stacks = inventory(ingredient.itemId()).listById(ingredient.itemId());
                for (int i = 0; i < ingredient.quantity(); i++) {
                    Item stack = stacks.get(i);
                    InventoryManipulator.removeFromSlot(client, stack.getInventoryType(), stack.getPosition(), stack.getQuantity(), false);
                }
            } else {
                InventoryManipulator.removeById(client, ItemConstants.getInventoryType(ingredient.itemId()),
                        ingredient.itemId(), ingredient.quantity(), false, false);
            }
        }
    }
}

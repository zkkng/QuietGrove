package server.content;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Locks an exchange's inventories in a consistent order until debit and delivery finish. */
public final class InventoryLocks implements AutoCloseable {
    private final List<Inventory> held = new ArrayList<>();
    private InventoryLocks() {}
    public static InventoryLocks acquire(Character chr, InventoryType... types) {
        InventoryLocks locks = new InventoryLocks();
        try {
            Arrays.stream(types).distinct().sorted(Comparator.comparingInt(InventoryType::getType)).forEach(type -> {
                Inventory inventory = chr.getInventory(type);
                if (inventory == null) throw new IllegalArgumentException("Unavailable inventory: " + type);
                inventory.lockInventory();
                locks.held.add(inventory);
            });
            return locks;
        } catch (RuntimeException failure) { locks.close(); throw failure; }
    }
    @Override public void close() {
        for (int i = held.size() - 1; i >= 0; i--) held.get(i).unlockInventory();
        held.clear();
    }
}

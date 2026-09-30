package server.crafting;

import server.crafting.TcgCatalog.Ingredient;
import server.crafting.TcgCatalog.Offer;

/** Exchange ordering is shared by the live inventory adapter and regression tests. */
public final class TcgExchange {
    private TcgExchange() {}

    interface Inventory {
        int activePetLevel();
        boolean has(Ingredient ingredient);
        boolean canReceive(Offer offer);
        boolean spend(int mesos);
        void refund(int mesos);
        boolean give(Offer offer);
        void take(Ingredient ingredient);
    }

    static String execute(Offer offer, Inventory inventory) {
        if (inventory.activePetLevel() < offer.petLevel()) {
            return "Please summon a pet of level " + offer.petLevel() + " or higher.";
        }
        for (Ingredient ingredient : offer.ingredients()) {
            if (!inventory.has(ingredient)) return "You are missing one or more ingredients. Equipped items cannot be used.";
        }
        if (!inventory.canReceive(offer)) {
            return "Please leave a free slot in the reward's inventory tab and check that you do not already own a one-of-a-kind reward.";
        }
        if (!inventory.spend(offer.mesos())) return "You do not have enough mesos.";
        boolean received = false;
        try {
            received = inventory.give(offer);
            if (!received) return "The reward could not be created. Your mesos and ingredients have been kept.";
        } finally {
            if (!received) inventory.refund(offer.mesos());
        }
        for (Ingredient ingredient : offer.ingredients()) inventory.take(ingredient);
        return "All done! Enjoy your #t" + offer.itemId() + "#.";
    }
}

package server.trainer;

import client.inventory.Equip;
import client.inventory.Item;
import server.ItemInformationProvider;
import soloMapling.itemPool.ItemDatabase;
import soloMapling.itemPool.ItemUtilities;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Versioned acquisition recipes. Existing stock is never rewritten or replenished on depletion. */
final class TrainerVenueStockPlan {
    static final String REVISION = "UTC_VARIETY_V2";
    record Recipe(int itemId, int successfulGloveScrolls) { }
    record Stock(Item item, long budgetValue) { }
    static List<Recipe> recipes(int map, int channel, LocalDate day) {
        List<Recipe> plan = new ArrayList<>();
        // One eligible Chaos day in seven per table, staggered across venues/channels.
        if (Math.floorMod(day.toEpochDay() + map + channel, 7) == 0) plan.add(new Recipe(2049100, 0));
        plan.add(new Recipe(2070006, 0)); // Ilbi
        plan.add(new Recipe(2070005, 0)); // Steely
        plan.add(new Recipe(1472026, 0)); // clean Scarab
        plan.add(new Recipe(1082002, 2)); // two authored successful +3 attack upgrades
        while (plan.size() < 31) plan.add(new Recipe(2040811, 0));
        return List.copyOf(plan);
    }
    static UUID assetId(int map, int channel, LocalDate day, int slot) {
        return UUID.nameUUIDFromBytes((REVISION + ":0:" + channel + ":" + map + ":" + day + ":" + slot).getBytes(StandardCharsets.US_ASCII));
    }
    static Stock materialize(Recipe recipe) {
        ItemDatabase catalog = ItemDatabase.getInstance();
        Integer base = catalog.getItemPrice(recipe.itemId());
        if (base == null || base <= 0) throw new IllegalStateException("Missing stock catalog value: " + recipe.itemId());
        Item item;
        long acquisitionCost = base;
        if (recipe.itemId() / 1_000_000 == 1) {
            item = ItemInformationProvider.getInstance().getEquipById(recipe.itemId());
            if (!(item instanceof Equip equip)) throw new IllegalStateException("Missing equipment template");
            if (recipe.successfulGloveScrolls() > 0) {
                if (recipe.itemId() != 1082002 || recipe.successfulGloveScrolls() != 2 || equip.getUpgradeSlots() < 2)
                    throw new IllegalStateException("Invalid fixed equipment recipe");
                var scroll = catalog.getScrollData(2040811);
                if (scroll == null || scroll.getSuccessRate() != 30 || scroll.getStatBonus() != 3 || scroll.getCurrentPrice() <= 0)
                    throw new IllegalStateException("Glove recipe catalog mismatch");
                equip.setWatk((short)(equip.getWatk() + 6));
                equip.setUpgradeSlots((byte)(equip.getUpgradeSlots() - 2)); equip.setLevel((byte)2);
                // Charge the expected acquisition cost, including failed 30% attempts.
                acquisitionCost += (2L * scroll.getCurrentPrice() * 100 + 29) / 30;
            }
        } else item = new Item(recipe.itemId(), (short)0, (short)1);
        Integer estimated = ItemUtilities.getItemMarketValue(item);
        if (estimated == null || estimated <= 0) throw new IllegalStateException("Missing exact-instance stock valuation");
        // The durable daily cap uses the larger estimate, never the cheap clean-item price.
        return new Stock(item, Math.max(acquisitionCost, estimated));
    }
}

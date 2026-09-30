package server.trainer;

import server.maps.MapItem;
import java.util.HashSet;
import java.util.Set;

/** Immutable filters for real drop pickup. */
final class TrainerLootOptions {
    final String includeText, excludeText, order;
    final Set<Integer> includeIds, excludeIds;
    final int minMeso, maxMeso;

    private TrainerLootOptions(String includeText, String excludeText, String order, int minMeso, int maxMeso) {
        if (!Set.of("nearest", "newest", "highest value", "owner stake first").contains(order)) throw new IllegalArgumentException("Unknown loot order.");
        if (minMeso < 0 || maxMeso < minMeso) throw new IllegalArgumentException("Meso amount range is invalid.");
        this.includeText = includeText; this.excludeText = excludeText; this.order = order;
        this.includeIds = ids(includeText); this.excludeIds = ids(excludeText);
        this.minMeso = minMeso; this.maxMeso = maxMeso;
    }

    static TrainerLootOptions defaults() { return new TrainerLootOptions("", "", "nearest", 0, Integer.MAX_VALUE); }

    static TrainerLootOptions parse(String include, String exclude, String order, int minMeso, int maxMeso) {
        return new TrainerLootOptions(include.trim(), exclude.trim(), order, minMeso, maxMeso);
    }

    private static Set<Integer> ids(String csv) {
        Set<Integer> ids = new HashSet<>();
        if (csv.isEmpty()) return ids;
        if (csv.length() > 256) throw new IllegalArgumentException("Item ID list is too long.");
        for (String piece : csv.split(",", -1)) {
            int id;
            try { id = Integer.parseInt(piece.trim()); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Item IDs must be comma-separated numbers."); }
            if (id <= 0) throw new IllegalArgumentException("Item IDs must be positive.");
            ids.add(id);
        }
        return Set.copyOf(ids);
    }

    boolean accepts(MapItem item) {
        if (item.getMeso() > 0) return item.getMeso() >= minMeso && item.getMeso() <= maxMeso;
        int id = item.getItem().getItemId();
        return !excludeIds.contains(id) && (includeIds.isEmpty() || includeIds.contains(id));
    }
}

package server.crafting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Server-owned prices and recipes; never accept an item or price from an NPC reply. */
public final class TcgCatalog {
    private TcgCatalog() {}

    public record Ingredient(int itemId, int quantity) {
        public boolean wholeStack() {
            return itemId / 10000 == 207 || itemId / 10000 == 233;
        }
    }

    public record Offer(String section, int itemId, int quantity, int mesos,
                        int petDays, int petLevel, List<Ingredient> ingredients) {}

    private static class Holder {
        private static final List<Offer> OFFERS = read(Path.of("scripts/npc/data/tcg-catalog.tsv"));
    }

    public static List<Offer> offers() {
        return Holder.OFFERS;
    }

    /** Preserve each crafter's role; Henesys additionally replaces unavailable physical codes. */
    public static List<Offer> offersForNpc(int npc, int map) {
        return offers().stream().filter(o -> {
            String section = o.section();
            if (npc == 9201082 && map == 100000000
                    && (section.startsWith("Buy -") || section.startsWith("Craft - Ridley ("))) return true;
            return switch (npc) {
                case 9201051 -> section.equals("Craft - Bosshunter") || section.equals("Craft - Forging manuals")
                        && (o.itemId() >= 4031822 && o.itemId() <= 4031825 || o.itemId() >= 4031907 && o.itemId() <= 4031912);
                case 9201052 -> section.equals("Craft - Forging manuals") && o.itemId() >= 4031826 && o.itemId() <= 4031829;
                case 9201082 -> section.equals("Craft - Equipment") || section.equals("Craft - Materia weapons") && o.itemId() != 4031761;
                case 9201083 -> section.equals("Craft - Materia weapons") && o.itemId() == 4031761;
                case 9201094 -> section.equals("Craft - Taru upgrades");
                case 9201101 -> section.startsWith("Craft - T-1337 ");
                case 9201102 -> section.equals("Craft - Stirgeman upgrades");
                case 9201106 -> section.equals("Craft - Zakum upgrades") || section.equals("Craft - Stone Denari");
                default -> false;
            };
        }).toList();
    }

    public static List<Offer> read(Path path) {
        try {
            return parse(Files.readAllLines(path));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read TCG catalog: " + path, e);
        }
    }

    static List<Offer> parse(List<String> lines) {
        List<Offer> offers = new ArrayList<>();
        var keys = new HashSet<String>();
        for (int line = 0; line < lines.size(); line++) {
            String text = lines.get(line).split("#", 2)[0].trim();
            if (text.isEmpty()) continue;
            try {
                String[] fields = text.split("\\|", -1);
                if (fields.length != 7) throw new IllegalArgumentException("Expected seven columns");
                String section = fields[0].trim();
                int item = positive(fields[1]);
                int quantity = positive(fields[2]);
                int mesos = positive(fields[3]);
                int days = Integer.parseInt(fields[4].trim());
                int level = Integer.parseInt(fields[5].trim());
                if (section.isEmpty() || item < 1000000 || item >= 6000000 || quantity > Short.MAX_VALUE
                        || days < 0 || days > 365 || level < 0 || level > 30
                        || (item / 1000000 == 1 && quantity != 1)
                        || (item / 10000 == 500 && (quantity != 1 || days == 0))
                        || (item / 10000 != 500 && days != 0)
                        || !keys.add(section + ":" + item)) {
                    throw new IllegalArgumentException("Invalid or duplicate offer");
                }
                List<Ingredient> ingredients = new ArrayList<>();
                var ids = new HashSet<Integer>();
                if (!fields[6].isBlank()) {
                    for (String part : fields[6].split(",")) {
                        String[] pair = part.trim().split(":", -1);
                        if (pair.length != 2) throw new IllegalArgumentException("Invalid ingredient");
                        int id = positive(pair[0]), count = positive(pair[1]);
                        if (id < 1000000 || id >= 5000000 || id == item || count > Short.MAX_VALUE || !ids.add(id)) {
                            throw new IllegalArgumentException("Invalid or duplicate ingredient");
                        }
                        ingredients.add(new Ingredient(id, count));
                    }
                }
                offers.add(new Offer(section, item, quantity, mesos, days, level, List.copyOf(ingredients)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid TCG catalog line " + (line + 1), e);
            }
        }
        if (offers.isEmpty()) throw new IllegalArgumentException("Empty TCG catalog");
        return List.copyOf(offers);
    }

    private static int positive(String value) {
        int result = Integer.parseInt(value.trim());
        if (result <= 0) throw new IllegalArgumentException("Expected a positive number");
        return result;
    }
}

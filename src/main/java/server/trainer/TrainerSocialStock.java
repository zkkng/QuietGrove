package server.trainer;

import client.Character;
import client.inventory.Item;
import server.ItemInformationProvider;
import soloMapling.itemPool.ItemUtilities;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Personal campaign stock is separate from equipment appearance and paid venue supply. */
public final class TrainerSocialStock {
    static final String GRANT = "SOCIAL_CAMPAIGN_V1";
    private TrainerSocialStock() { }

    public static String name(int mapId, int channel, int slot) {
        if (mapId < 0 || channel < 1 || channel > 99 || slot < 0 || slot >= 1296)
            throw new IllegalArgumentException("social identity");
        return "TS" + String.format(Locale.ROOT, "%6s", Integer.toString(mapId, 36)).replace(' ', '0')
                + String.format(Locale.ROOT, "%02d", channel)
                + String.format(Locale.ROOT, "%2s", Integer.toString(slot, 36)).replace(' ', '0');
    }

    static boolean eligible(String name, int mapId, int channel) {
        if (name == null || name.length() != 12 || !name.matches("TS[0-9a-z]{6}[0-9]{2}[0-9a-z]{2}")) return false;
        try { return name.equals(name(mapId, channel, Integer.parseInt(name.substring(10), 36))); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static int[] itemIds(String name) {
        // One collector in 32 identities receives a Chaos Scroll once for the campaign.
        // Other identities hold three ordinary scroll/star stakes, never a refill on invitation.
        return new int[]{2040811, 2070005, Math.floorMod(name.hashCode(), 32) == 0 ? 2049100 : 2040811};
    }

    static void ensure(Character bot) throws SQLException {
        var map = bot.getMap();
        if (map == null || map.getWorld() != 0 || !eligible(bot.getName(), map.getId(), map.getChannelServer().getId()))
            throw new IllegalArgumentException("social participant has no durable town identity");
        if (TrainerVenueLedger.hasPersonalGrant(map.getId(), map.getChannelServer().getId(), bot.getName())) return;
        List<Item> items = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (int id : itemIds(bot.getName())) {
            Item item = new Item(id, (short) 0, (short) 1);
            String display = ItemInformationProvider.getInstance().getName(id);
            Integer value = ItemUtilities.getItemMarketValue(item);
            if (display == null || display.isBlank() || value == null || value <= 0)
                throw new IllegalStateException("Personal stake content/valuation missing: " + id);
            items.add(item); values.add(value.longValue());
        }
        TrainerVenueLedger.grantPersonalStock(map.getId(), map.getChannelServer().getId(), bot.getName(), items, values);
    }
}

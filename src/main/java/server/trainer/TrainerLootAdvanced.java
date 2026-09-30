package server.trainer;

import server.maps.MapItem;
import java.util.*;
import soloMapling.itemPool.ItemUtilities;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

record TrainerLootAdvanced(boolean nearbyAuto, boolean petItems, boolean petMesos, int petIndex,
                           boolean feeder, int feedThreshold, String name, String category,
                           long minValue, String source, int itemQuota, int mesoQuota) {
    static final TrainerLootAdvanced OFF = new TrainerLootAdvanced(false,false,false,0,false,50,"","all",0,"all",25,25);
    TrainerLootAdvanced {
        name = name.strip().toLowerCase(Locale.ROOT);
        if (petIndex < 0 || petIndex > 2 || feedThreshold < 1 || feedThreshold > 75 || name.length() > 64
                || minValue < 0 || minValue > Integer.MAX_VALUE || itemQuota < 0 || itemQuota > 25 || mesoQuota < 0 || mesoQuota > 25
                || !Set.of("all","equip","scroll","star","chair","use","etc").contains(category)
                || !Set.of("all","bot","venue","player","monster").contains(source))
            throw new IllegalArgumentException("Invalid advanced loot settings");
    }
    static TrainerLootAdvanced parse(Map<String,String> fields) {
        if (!fields.keySet().equals(Set.of("nearbyAuto","petItems","petMesos","petIndex","feeder","feedThreshold","name","category","minValue","source","itemQuota","mesoQuota")))
            throw new IllegalArgumentException("Incomplete or unknown loot settings");
        for (String key : List.of("nearbyAuto","petItems","petMesos","feeder")) if (!Set.of("0","1").contains(fields.get(key))) throw new IllegalArgumentException("Flags must be 0 or 1");
        return new TrainerLootAdvanced(fields.get("nearbyAuto").equals("1"),fields.get("petItems").equals("1"),fields.get("petMesos").equals("1"),
                Integer.parseInt(fields.get("petIndex")),fields.get("feeder").equals("1"),Integer.parseInt(fields.get("feedThreshold")),fields.get("name"),fields.get("category"),
                Long.parseLong(fields.get("minValue")),fields.get("source"),Integer.parseInt(fields.get("itemQuota")),Integer.parseInt(fields.get("mesoQuota")));
    }
    boolean automated() { return nearbyAuto || petItems || petMesos; }
    TrainerLootAdvanced paused() { return new TrainerLootAdvanced(false,false,false,petIndex,false,feedThreshold,name,category,minValue,source,itemQuota,mesoQuota); }
    static long value(MapItem drop) {
        if (drop.getMeso() > 0) return drop.getMeso();
        try { Integer value = ItemUtilities.getItemMarketValue(drop.getItem()); return value == null || value <= 0 ? -1 : (long)value * Math.max(1,drop.getItem().getQuantity()); }
        catch (RuntimeException unknown) { return -1; }
    }
    boolean accepts(MapItem drop) {
        boolean bot = drop.getDropper() instanceof client.Character owner && isBot(owner);
        if (!switch (source) { case "bot" -> bot; case "venue" -> drop.getVenueAssetId()!=null; case "player" -> drop.isPlayerDrop()&&!bot; case "monster" -> !drop.isPlayerDrop()&&!bot; default -> true; }) return false;
        if (drop.getMeso() > 0) return true;
        int id = drop.getItemId();
        if (!switch(category) { case "equip" -> id/1_000_000==1; case "scroll" -> id/10_000==204; case "star" -> id/10_000==207; case "chair" -> id/10_000==301; case "use" -> id/1_000_000==2; case "etc" -> id/1_000_000==4; default -> true; }) return false;
        if (minValue > 0 && value(drop) < minValue) return false; // Unknown is ineligible, not a zero-price estimate.
        if (!name.isEmpty()) {
            String actual = server.ItemInformationProvider.getInstance().getName(id);
            if (actual == null || !actual.toLowerCase(Locale.ROOT).contains(name)) return false;
        }
        return true;
    }
    void status(Map<String,String> result) {
        result.put("lootToolsReady","1"); result.put("nearbyAuto",nearbyAuto?"1":"0"); result.put("petItems",petItems?"1":"0"); result.put("petMesos",petMesos?"1":"0");
        result.put("petIndex",Integer.toString(petIndex)); result.put("feeder",feeder?"1":"0"); result.put("feedThreshold",Integer.toString(feedThreshold));
        result.put("lootName",name); result.put("lootCategory",category); result.put("lootMinValue",Long.toString(minValue)); result.put("lootSource",source);
        result.put("itemQuota",Integer.toString(itemQuota)); result.put("mesoQuota",Integer.toString(mesoQuota));
    }
}

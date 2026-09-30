package server.trainer;

import java.util.Map;
import java.util.Set;

/** Parse the entire profile before touching a live session. */
record TrainerProfile(Core core, TrainerAutoPotion.Options potions, TrainerPowerOptions powers,
                      TrainerLootAdvanced loot, TrainerMobOptions mobs, int pointMap, TrainerPickupOptions pickup, TrainerRegenOptions regen) {
    static TrainerProfile parse(Map<String,String> fields) {
        var keys=new java.util.HashSet<>(fields.keySet()); keys.remove("pickup"); keys.remove("regen");
        if (!keys.equals(Set.of("core","potions","powers","loot","mobs","pointMap")))
            throw new IllegalArgumentException("Incomplete or unknown profile sections");
        return new TrainerProfile(Core.parse(TrainerBridge.decode(fields.get("core"))),
                potions(TrainerBridge.decode(fields.get("potions"))),
                TrainerPowerOptions.parse(TrainerBridge.decode(fields.get("powers"))),
                TrainerLootAdvanced.parse(TrainerBridge.decode(fields.get("loot"))),
                TrainerMobOptions.parse(TrainerBridge.decode(fields.get("mobs"))),Integer.parseInt(fields.get("pointMap")),fields.containsKey("pickup")?TrainerPickupOptions.parse(TrainerBridge.decode(fields.get("pickup"))):TrainerPickupOptions.OFF, fields.containsKey("regen")?TrainerRegenOptions.parse(TrainerBridge.decode(fields.get("regen"))):TrainerRegenOptions.DEFAULT);
    }
    static boolean flag(Map<String,String> f,String key) {
        String value=f.getOrDefault(key,"0");
        if (!value.equals("0")&&!value.equals("1")) throw new IllegalArgumentException("Flags must be 0 or 1");
        return value.equals("1");
    }
    static TrainerAutoPotion.Options potions(Map<String,String> f) {
        if (!f.keySet().equals(Set.of("hp","mp","hpThreshold","mpThreshold","hpItem","mpItem","reserve","interval")))
            throw new IllegalArgumentException("Incomplete or unknown potion settings");
        return new TrainerAutoPotion.Options(flag(f,"hp"),flag(f,"mp"),Integer.parseInt(f.get("hpThreshold")),
                Integer.parseInt(f.get("mpThreshold")),Integer.parseInt(f.get("hpItem")),Integer.parseInt(f.get("mpItem")),
                Integer.parseInt(f.get("reserve")),Integer.parseInt(f.get("interval")));
    }
    record Core(Map<String,String> fields, TrainerLootOptions loot, String vacMode, int damage, boolean oneHit) {
        private static final Set<String> KEYS=Set.of("vac","vacMode","itemVac","mesoVac","lootOnKey","lootRadius","lootBatch","lootOrder","includeIds","excludeIds","minMeso","maxMeso","fma","fmaDamage","fmaOneHit","rapid","hpGod","hpRegen","mpRegen","interval");
        static Core parse(Map<String,String> f) {
            if (!f.keySet().equals(KEYS)) throw new IllegalArgumentException("Incomplete or unknown core settings");
            String mode=f.get("vacMode");
            if (!Set.of("front","left wall","right wall").contains(mode)) throw new IllegalArgumentException("Unknown Mob Vac mode");
            Core result=new Core(Map.copyOf(f),TrainerLootOptions.parse(f.get("includeIds"),f.get("excludeIds"),f.get("lootOrder"),
                    Integer.parseInt(f.get("minMeso")),Integer.parseInt(f.get("maxMeso"))),mode,
                    Integer.parseInt(f.get("fmaDamage")),flag(f,"fmaOneHit"));
            result.apply(new TrainerLease(0),0); // exercise all lease ranges without modifying effective state
            return result;
        }
        void apply(TrainerLease lease,long now) {
            Map<String,String> f=fields;
            lease.fmaPower(damage,oneHit);
            lease.configure(flag(f,"vac"),flag(f,"itemVac"),flag(f,"mesoVac"),flag(f,"lootOnKey"),
                    Integer.parseInt(f.get("lootRadius")),Integer.parseInt(f.get("lootBatch")),flag(f,"fma"),flag(f,"rapid"),
                    flag(f,"hpGod"),flag(f,"hpRegen"),flag(f,"mpRegen"),Integer.parseInt(f.get("interval")),now);
        }
    }
}

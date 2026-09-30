package server.trainer;

import client.Disease;
import java.util.*;

/** Immutable named overlays; no permanent character stats, skillbook or inventory are rewritten. */
record TrainerPowerOptions(boolean noMpCost, boolean noAmmo, boolean zeroCooldown,
                           Set<Integer> cooldownSkills, Set<Disease> immunity,
                           int damageMultiplier, boolean oneHit, boolean accuracy, String roll) {
    static final Set<Disease> SUPPORTED_DISEASES = Set.of(Disease.POISON, Disease.STUN, Disease.SEAL,
            Disease.CURSE, Disease.SLOW, Disease.DARKNESS, Disease.WEAKEN, Disease.CONFUSE, Disease.SEDUCE, Disease.ZOMBIFY);
    static final TrainerPowerOptions OFF = new TrainerPowerOptions(false, false, false,
            Set.of(), Set.of(), 1, false, false, "normal");
    TrainerPowerOptions {
        cooldownSkills = Set.copyOf(cooldownSkills); immunity = Set.copyOf(immunity);
        if (cooldownSkills.size() > 16 || cooldownSkills.stream().anyMatch(id -> id <= 0 || id == 5221006 || id == 5221999)
                || !SUPPORTED_DISEASES.containsAll(immunity) || damageMultiplier < 0 || damageMultiplier > 100
                || !Set.of("normal", "minimum", "average", "maximum").contains(roll))
            throw new IllegalArgumentException("Invalid trainer power settings");
        if (zeroCooldown && cooldownSkills.isEmpty()) throw new IllegalArgumentException("Choose learned skill IDs for cooldown control");
    }
    static TrainerPowerOptions parse(Map<String,String> fields) {
        if (!fields.keySet().equals(Set.of("noMpCost","noAmmo","zeroCooldown","cooldownSkills","immunity","damageMultiplier","oneHit","accuracy","roll")))
            throw new IllegalArgumentException("Incomplete or unknown power settings");
        Set<Integer> skills = new HashSet<>();
        if (!fields.get("cooldownSkills").isBlank()) for (String part : fields.get("cooldownSkills").split(",", -1)) skills.add(Integer.parseInt(part.trim()));
        Set<Disease> diseases = EnumSet.noneOf(Disease.class);
        if (!fields.get("immunity").isBlank()) for (String part : fields.get("immunity").split(",", -1)) diseases.add(Disease.valueOf(part.trim().toUpperCase(Locale.ROOT)));
        return new TrainerPowerOptions(flag(fields,"noMpCost"), flag(fields,"noAmmo"), flag(fields,"zeroCooldown"), skills, diseases,
                Integer.parseInt(fields.get("damageMultiplier")), flag(fields,"oneHit"), flag(fields,"accuracy"), fields.get("roll"));
    }
    private static boolean flag(Map<String,String> fields,String key) {
        String value = fields.get(key);
        if (!"0".equals(value) && !"1".equals(value)) throw new IllegalArgumentException("Flags must be 0 or 1");
        return "1".equals(value);
    }
    boolean cooldown(int skill) { return zeroCooldown && cooldownSkills.contains(skill); }
    void status(Map<String,String> result) {
        result.put("powerOptionsReady","1"); result.put("noMpCost",noMpCost?"1":"0"); result.put("noAmmo",noAmmo?"1":"0");
        result.put("zeroCooldown",zeroCooldown?"1":"0"); result.put("cooldownSkills",cooldownSkills.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        result.put("immunity",immunity.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")));
        result.put("damageMultiplier",Integer.toString(damageMultiplier)); result.put("oneHit",oneHit?"1":"0");
        result.put("accuracy",accuracy?"1":"0"); result.put("damageRoll",roll);
    }
    int damage(int original, long maximum, int hp, int line) {
        if (oneHit) return line == 0 ? Math.max(1,hp) : 0;
        long value = original < 0 ? original & Integer.MAX_VALUE : original;
        if (value == 0 && !accuracy) return 0;
        maximum = Math.max(1,Math.min(199_999L,maximum));
        if (value == 0) value = Math.max(1,maximum / 2);
        value = switch (roll) { case "minimum" -> Math.max(1,maximum / 2); case "average" -> maximum * 3 / 4; case "maximum" -> maximum; default -> value; };
        return (int)Math.min(199_999L,Math.max(0,value) * damageMultiplier);
    }
}

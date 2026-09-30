package soloMapling.ArtificialPlayer.CompanionSystem;

import java.util.*;

/** Versioned local content contract. Template IDs never identify a live encounter. */
public record BossDefinition(String key, int version, String displayName, Set<String> aliases,
        Set<Integer> roots, Set<Integer> phases, Set<Integer> finals, List<Integer> maps,
        Type type, int minimumLevel, int gatherMap, String eventScript, String source,
        long absenceMs, long deadlineMs) {
    public enum Type { FIELD, AREA_SEARCH, BOAT, INSTANCE, EXPEDITION }
    public BossDefinition {
        if (key == null || key.isBlank() || version < 1 || displayName == null || aliases == null
                || roots == null || roots.isEmpty() || phases == null || finals == null || finals.isEmpty()
                || maps == null || maps.isEmpty() || type == null || minimumLevel < 1 || minimumLevel > 200
                || gatherMap < 0 || source == null || source.isBlank() || absenceMs < 1000 || deadlineMs < absenceMs)
            throw new IllegalArgumentException("Incomplete boss definition: " + key);
        aliases = Set.copyOf(aliases); roots = Set.copyOf(roots); phases = Set.copyOf(phases);
        finals = Set.copyOf(finals); maps = List.copyOf(maps);
        if (!phases.containsAll(roots) || !phases.containsAll(finals)
                || phases.stream().anyMatch(i -> i <= 0 || i > 9999999)
                || maps.stream().anyMatch(i -> i < 0 || i > 999999999)
                || type != Type.FIELD && type != Type.AREA_SEARCH && (eventScript == null || eventScript.isBlank()))
            throw new IllegalArgumentException("Invalid boss adapter: " + key);
    }
    public boolean restricted() { return type == Type.INSTANCE || type == Type.EXPEDITION; }
    public boolean combatTemplate(int template) {
        if(key.equals("horntail") && (template==8810026 || template>=8810010 && template<=8810017)) return false;
        if (key.equals("instance-balrog")) return template >= 8830000 && template <= 8830002;
        if (key.equals("easy-balrog")) return template >= 8830007 && template <= 8830009;
        return phases.contains(template);
    }
    public boolean attackTemplate(int template) {
        return combatTemplate(template) && !(key.equals("horntail") && template==8810018);
    }
    public String contentEra() { return "GMS v83, local WZ and source adapters"; }
    public BossThreats.Profile threats() { return BossThreats.profile(this); }
    public String rewardPolicy() { return "Ordinary damage, useful party support, quest, EXP and drop attribution once"; }
    public String spawnPolicy() { return restricted() ? "Canonical party/expedition entry and existing summon trigger"
            : type == Type.BOAT ? "Scheduled lawful transport; encounter may not appear" : "Existing world spawn lifecycle; no scheduler prediction"; }
}

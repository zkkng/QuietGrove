package soloMapling.ArtificialPlayer.CompanionSystem;

import provider.*;
import provider.wz.WZFiles;
import java.util.*;

/** Curated GMS v83 catalog, verified against this checkout's WZ and source spawn scripts. */
public final class BossRegistry {
    private static final List<BossDefinition> CATALOG = List.of(
        field("mano", "Mano", 2220000, 20, "scripts/event/AreaBossMano.js", 104000400),
        field("stumpy", "Stumpy", 3220000, 35, "scripts/event/AreaBossStumpy.js", 101030404),
        field("king-clang", "King Clang", 5220001, 55, "scripts/event/AreaBossKingClang.js", 110040000),
        field("mushmom", "Mushmom", 6130101, 60, "wz/Map.wz/Map/Map1/100000005.img.xml", 100000005),
        field("jr-balrog", "Jr. Balrog", 8130100, 80, "wz/Map.wz/Map/Map1/105090900.img.xml", 105090900),
        field("manon", "Manon", 8180000, 105, "wz/Map.wz/Map/Map2/240020401.img.xml", 240020401, 240020402),
        field("griffey", "Griffey", 8180001, 105, "wz/Map.wz/Map/Map2/240020101.img.xml", 240020101, 240020102),
        field("dyle", "Dyle", 6220000, 65, "scripts/event/AreaBossDyle.js", 107000300),
        field("zombie-mushmom", "Zombie Mushmom", 6300005, 65, "wz/Map.wz/Map/Map1/105070002.img.xml", 105070002),
        field("blue-mushmom", "Blue Mushmom", 9400205, 90, "wz/Map.wz/Map/Map8/800010100.img.xml", 800010100),
        field("headless-horseman", "Headless Horseman", 9400549, 101, "wz/Map.wz/Map/Map6/610010005.img.xml",
                610010005, 610010010, 610010011, 610010013, 610010200, 610010201, 610010202),
        field("bigfoot", "Bigfoot", 9400575, 110, "wz/Map.wz/Map/Map6/610010005.img.xml",
                610010005, 610010012, 610010013, 610010100, 610010101, 610010102, 610010103, 610010104),
        field("anego", "Female Boss", 9400121, 130, "wz/Map.wz/Map/Map8/801040003.img.xml", 801040003),
        adapted("boat-balrog", "Crimson Balrog", Set.of("crimson balrog", "boat balrog", "crog", "cbalrog"),
                Set.of(8150000), Set.of(8150000), Set.of(8150000), List.of(200090010, 200090000),
                BossDefinition.Type.BOAT, 100, 101000300, "Boats", 30 * 60_000L),
        adapted("papulatus", "Papulatus", Set.of("papulatus", "pap", "papulatus clock"),
                Set.of(8500000, 8500001), Set.of(8500000, 8500001, 8500002), Set.of(8500002), List.of(220080001),
                BossDefinition.Type.INSTANCE, 80, 220080000, "PapulatusBattle", 45 * 60_000L),
        adapted("zakum", "Zakum", Set.of("zakum", "zak", "zkm"), Set.of(8800000),
                range(8800000, 8800010), Set.of(8800002), List.of(280030000),
                BossDefinition.Type.EXPEDITION, 50, 211042400, "ZakumBattle", 120 * 60_000L),
        adapted("horntail", "Horntail", Set.of("horntail", "horn tail", "ht"), Set.of(8810000, 8810001, 8810018, 8810026),
                union(range(8810000, 8810018),Set.of(8810026)), Set.of(8810018), List.of(240060000, 240060100, 240060200),
                BossDefinition.Type.EXPEDITION, 100, 240050400, "HorntailBattle", 120 * 60_000L),
        adapted("instance-balrog", "Instanced Balrog", Set.of("instanced balrog", "instance balrog", "balrog expedition", "normal balrog"),
                Set.of(8830000, 8830001, 8830002, 8830006), range(8830000, 8830006), Set.of(8830000), List.of(105100300, 105100301),
                BossDefinition.Type.EXPEDITION, 50, 105100100, "BalrogBattle", 60 * 60_000L),
        adapted("easy-balrog", "Easy Balrog", Set.of("easy balrog"), Set.of(8830007, 8830008, 8830009, 8830013),
                range(8830007, 8830013), Set.of(8830007), List.of(105100400, 105100401),
                BossDefinition.Type.EXPEDITION, 50, 105100100, "BalrogBattle_Easy", 60 * 60_000L)
    );
    private BossRegistry() {}
    private static Set<Integer> union(Set<Integer> first,Set<Integer> second) {
        var result=new HashSet<>(first);result.addAll(second);return Set.copyOf(result);
    }
    public static List<BossDefinition> all() { return CATALOG; }
    public static boolean catalogMonster(int template) { return CATALOG.stream().anyMatch(d -> d.phases().contains(template)); }
    public static BossDefinition get(String key) {
        return CATALOG.stream().filter(d -> d.key().equals(key)).findFirst().orElseThrow();
    }
    public static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('\u2019', '\'').replaceAll("['’]s\\b", "")
                .replaceAll("[^\\p{L}\\p{N}>]+", " ").trim().replaceAll("\\s+", " ");
    }
    private static Set<Integer> range(int first, int last) {
        Set<Integer> out = new LinkedHashSet<>(); for (int i = first; i <= last; i++) out.add(i); return out;
    }
    private static BossDefinition field(String key, String name, int mob, int level, String source, Integer... maps) {
        Set<String> aliases = new HashSet<>(Set.of(normalize(name)));
        switch (key) {
            case "jr-balrog" -> aliases.addAll(Set.of("jr balrog", "junior balrog", "jrb", "jr rog"));
            case "king-clang" -> aliases.add("clang");
            case "zombie-mushmom" -> aliases.add("zmm");
            case "blue-mushmom" -> aliases.add("bmm");
            case "headless-horseman" -> aliases.add("hh");
            case "bigfoot" -> aliases.add("bf");
            case "anego" -> aliases.addAll(Set.of("anego", "female boss"));
        }
        return new BossDefinition(key, 1, name, aliases, Set.of(mob), Set.of(mob), Set.of(mob), List.of(maps),
                maps.length > 2 ? BossDefinition.Type.AREA_SEARCH : BossDefinition.Type.FIELD,
                level, maps[0], "", source, 60_000, 30 * 60_000);
    }
    private static BossDefinition adapted(String key, String name, Set<String> aliases, Set<Integer> roots,
            Set<Integer> phases, Set<Integer> finals, List<Integer> maps, BossDefinition.Type type,
            int level, int gather, String script, long deadline) {
        return new BossDefinition(key, 1, name, aliases, roots, phases, finals, maps, type, level,
                gather, script, "scripts/event/" + script + ".js", type == BossDefinition.Type.BOAT ? 10*60_000 : 60_000, deadline);
    }
    /** Fail closed before exposing any entry with broken WZ, phase graph or adapter source. */
    public static List<String> validate() {
        List<String> errors = new ArrayList<>();
        Set<String> aliases = new HashSet<>();
        DataProvider maps = DataProviderFactory.getDataProvider(WZFiles.MAP);
        DataProvider mobs = DataProviderFactory.getDataProvider(WZFiles.MOB);
        Data skills = DataProviderFactory.getDataProvider(WZFiles.SKILL).getData("MobSkill.img");
        for (BossDefinition d : CATALOG) {
            for (String alias : d.aliases()) if (!aliases.add(normalize(alias))) errors.add(d.key() + ": duplicate alias " + alias);
            if (!java.nio.file.Files.isRegularFile(java.nio.file.Path.of(d.source()))) errors.add(d.key() + ": missing source");
            for (int mid : d.maps()) {
                Data map = maps.getData("Map/Map" + mid / 100000000 + "/" + String.format("%09d.img", mid));
                if (map == null || map.getChildByPath("portal") == null) errors.add(d.key() + ": missing map/portals " + mid);
            }
            Data gather = maps.getData("Map/Map" + d.gatherMap()/100000000 + "/" + String.format("%09d.img",d.gatherMap()));
            if (gather == null || gather.getChildByPath("portal") == null) errors.add(d.key()+": missing gather portals");
            String sourceText;
            try { sourceText = java.nio.file.Files.readString(java.nio.file.Path.of(d.source())); }
            catch (java.io.IOException failure) { sourceText = ""; }
            boolean hasRootSource = false;
            for (int root : d.roots()) if (sourceContains(sourceText,root)) hasRootSource = true;
            if (!d.restricted() && !hasRootSource) errors.add(d.key()+": source does not contain selected root");
            for (int id : d.phases()) {
                if (d.attackTemplate(id) && soloMapling.ArtificialPlayer.GCMoveSystem.BotMobHitboxProvider
                        .getMobBounds(id,new java.awt.Point(),false)==null)
                    errors.add(d.key()+": missing body hitbox " + id);
                Data mob = mobs.getData(String.format("%07d.img",id));
                if (mob == null || DataTool.getInt("info/maxHP",mob,0) < 1 || DataTool.getInt("info/level",mob,0) < 1)
                    errors.add(d.key() + ": missing monster/stats " + id);
                else {
                    detectCycle(d, id, new HashSet<>(), errors,mobs);
                    Data actions = mob.getChildByPath("info/skill");
                    if (actions != null) for (Data action : actions) {
                        int type = DataTool.getInt("skill",action,0), level = DataTool.getInt("level",action,0);
                        if (server.life.MobSkillType.from(type).isEmpty() || skills.getChildByPath(type+"/level/"+level) == null)
                            errors.add(d.key()+": missing skill " + type+"/"+level);
                    }
                }
            }
        }
        return List.copyOf(errors);
    }
    private static boolean sourceContains(String source, int template) {
        return java.util.regex.Pattern.compile("(?<![0-9])"+template+"(?![0-9])").matcher(source).find();
    }
    private static void detectCycle(BossDefinition d, int id, Set<Integer> stack, List<String> errors, DataProvider mobs) {
        if (!stack.add(id)) { errors.add(d.key() + ": phase cycle " + id); return; }
        Data mob = mobs.getData(String.format("%07d.img",id));
        Data revives = mob == null ? null : mob.getChildByPath("info/revive");
        if (revives != null) for (Data next : revives) if (d.phases().contains(DataTool.getInt(next)))
            detectCycle(d,DataTool.getInt(next),stack,errors,mobs);
        stack.remove(id);
    }
}

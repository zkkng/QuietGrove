package soloMapling.ArtificialPlayer.CompanionSystem;

import java.util.*;
import java.util.regex.Pattern;

/** Whole aliases and whole recipients; ordinary discussion has no gameplay side effects. */
public final class BossIntentParser {
    public enum Kind { NONE, RECRUIT, CLARIFY, UNSUPPORTED, CANCEL, GATHER, LOOT, STATUS }
    public enum Role { ANY, FRONTLINE, RANGED, MAGIC, SUPPORT }
    public record Intent(Kind kind, String bossKey, Integer recipient, Role role,
                         BossObjective.Mode mode, Integer gatherMap, String loot) {
        static Intent none() { return new Intent(Kind.NONE, "", null, Role.ANY, BossObjective.Mode.HUNT_TEMPLATE_IN_AREA, null, ""); }
    }
    private static final Pattern ACTION = Pattern.compile("\\br\\s*>|\\blfm\\b|\\b(?:kill|fight|hunt|help|need|recruit|come|join)\\b");
    private static final Pattern NEGATIVE = Pattern.compile("\\b(?:don t|dont|do not|not|never|stop|cancel|remember|used to|yesterday|story|said|say|quote)\\b");
    private BossIntentParser() {}
    public static Intent parse(String message, Map<Integer, String> names) {
        if (message == null || names == null) return Intent.none();
        String raw = message.trim();
        if (raw.isEmpty() || "!/@#$".indexOf(raw.charAt(0)) >= 0 || raw.contains("\"") || raw.contains("“") || raw.contains("`")) return Intent.none();
        String text = BossRegistry.normalize(raw);
        Integer recipient = null;
        for (var entry : names.entrySet().stream().sorted(Comparator.<Map.Entry<Integer,String>>comparingInt(e -> e.getValue().length()).reversed().thenComparingInt(Map.Entry::getKey)).toList()) {
            String name = BossRegistry.normalize(entry.getValue());
            if (name.isEmpty()) continue;
            var matcher = whole(name).matcher(text);
            if (matcher.find()) {
                if (recipient != null && !recipient.equals(entry.getKey())) return Intent.none();
                recipient = entry.getKey(); text = matcher.replaceAll(" ").trim();
            }
        }
        if (text.matches("(?:cancel (?:the )?(?:boss|hunt|boss hunt)|stop hunting|retreat|dismiss companions)"))
            return new Intent(Kind.CANCEL, "", recipient, Role.ANY, BossObjective.Mode.HUNT_TEMPLATE_IN_AREA, null, "");
        if (text.matches("(?:boss|hunt) status")) return new Intent(Kind.STATUS, "", null, Role.ANY, null, null, "");
        if (text.matches("(?:gather|meet) (?:at|in) [0-9]{9}"))
            return new Intent(Kind.GATHER, "", null, Role.ANY, null, Integer.valueOf(text.substring(text.length()-9)), "");
        if (text.matches("(?:boss )?loot (?:for me|shared|leave)"))
            return new Intent(Kind.LOOT, "", null, Role.ANY, null, null, text.substring(text.indexOf("loot ")+5));
        if (NEGATIVE.matcher(text).find()) return Intent.none();
        if (!ACTION.matcher(text).find() && !text.matches("(?:crimson balrog|boat balrog|crog|cbalrog) here")) return Intent.none();
        var unsupported = whole("dojo|quest variant|chaos|pink bean|cwkpq|scar lion|scarlion|targa|the boss|bodyguard|pianus|leviathan|dodo|lilynouch|lyka|ergoth|alishar|lord pirate|papa pixie|latanica|krexel").matcher(text);
        if (unsupported.find()) return new Intent(Kind.UNSUPPORTED,unsupported.group(),recipient,Role.ANY,null,null,"");
        record Match(BossDefinition definition, String alias) {}
        List<Match> matches = new ArrayList<>();
        for (var d : BossRegistry.all()) for (String alias : d.aliases())
            if (whole(alias).matcher(text).find()) matches.add(new Match(d, alias));
        // Longest alias wins over its own suffix (Zombie Mushmom / Mushmom, Papulatus Clock / Papulatus).
        matches.sort(Comparator.comparingInt((Match m) -> m.alias().length()).reversed());
        String remaining = text;
        Set<String> keys = new LinkedHashSet<>();
        for (Match m : matches) if (whole(m.alias()).matcher(remaining).find()) {
            keys.add(m.definition().key()); remaining = whole(m.alias()).matcher(remaining).replaceAll(" ");
        }
        if (keys.size() > 1) return new Intent(Kind.CLARIFY, "", recipient, Role.ANY, null, null, "");
        if (keys.isEmpty()) {
            if (whole("balrog").matcher(text).find()) return new Intent(Kind.CLARIFY, "balrog", recipient, Role.ANY, null, null, "");
            return Intent.none();
        }
        Role role = whole("healer|priest|bishop|support|hs").matcher(text).find() ? Role.SUPPORT
                : whole("tank|frontline|warrior").matcher(text).find() ? Role.FRONTLINE
                : whole("ranged|archer|bowman").matcher(text).find() ? Role.RANGED
                : whole("mage|magic").matcher(text).find() ? Role.MAGIC : Role.ANY;
        BossObjective.Mode mode = whole("stay|continuing|keep partying").matcher(text).find() ? BossObjective.Mode.CONTINUING_PARTY
                : whole("here|this one|current").matcher(text).find() ? BossObjective.Mode.HELP_CURRENT_ENCOUNTER
                : BossObjective.Mode.HUNT_TEMPLATE_IN_AREA;
        var gather = Pattern.compile("\\b(?:gather|meet) (?:at|in) ([0-9]{9})\\b").matcher(text);
        return new Intent(Kind.RECRUIT, keys.iterator().next(), recipient, role, mode,
                gather.find() ? Integer.valueOf(gather.group(1)) : null, "");
    }
    private static Pattern whole(String alias) { return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + alias + ")(?![\\p{L}\\p{N}_])"); }
}

package soloMapling.ArtificialPlayer.CompanionSystem;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Deterministic chat intent. Names are removed before keyword matching. No online inference. */
public final class RecruitIntentParser {
    public enum Kind { NONE, RECRUIT, CANCEL }
    public record Intent(Kind kind, Integer namedBotId, String fingerprint) {
        public static Intent none() { return new Intent(Kind.NONE, null, ""); }
    }

    private static final Pattern COMMAND = Pattern.compile("^[!/@#$]");
    private static final Pattern NEGATION = Pattern.compile("\\b(?:no|not|never|dont|don't|do not|stop|cancel)\\b");
    private static final Pattern CANCEL = Pattern.compile(
            "\\b(?:cancel recruitment|stop recruiting|dismiss companions|leave my party|stop following)\\b");
    private static final Pattern RECRUIT = Pattern.compile(
            "(?:\\b(?:lfm)\\b|\\br\\s*>).*(?:\\b(?:party|train|training|grind|grinding|level)\\b)"
            + "|\\bparty up\\b|\\b(?:train|grind) together\\b|\\bhelp me level\\b"
            + "|\\b(?:come )?(?:train|grind) with me\\b");

    private RecruitIntentParser() {}

    public static Intent parse(String message, Map<Integer, String> botNames) {
        if (message == null || botNames == null) return Intent.none();
        String text = message.trim().toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        if (text.isEmpty() || COMMAND.matcher(text).find()) return Intent.none();
        Integer target = null;
        for (var entry : botNames.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Integer, String>>comparingInt(e -> e.getValue().length())
                        .reversed().thenComparingInt(Map.Entry::getKey)).toList()) {
            String name = entry.getValue().toLowerCase(Locale.ROOT);
            if (name.isBlank()) continue;
            Pattern wholeName = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(name)
                    + "(?![\\p{L}\\p{N}_])");
            var matcher = wholeName.matcher(text);
            if (matcher.find()) {
                if (target != null && !target.equals(entry.getKey())) return Intent.none();
                target = entry.getKey();
                text = matcher.replaceAll(" ");
            }
        }
        text = text.replaceAll("[^\\p{L}\\p{N}'>]+", " ").trim().replaceAll("\\s+", " ");
        if (CANCEL.matcher(text).find()) return new Intent(Kind.CANCEL, target, text);
        if (NEGATION.matcher(text).find() || !RECRUIT.matcher(text).find()) return Intent.none();
        return new Intent(Kind.RECRUIT, target, text);
    }
}

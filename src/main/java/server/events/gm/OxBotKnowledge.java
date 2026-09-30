package server.events.gm;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.*;
import java.util.regex.Pattern;

/** Imperfect recall of public monster facts. Never reads the quiz answer or explanation fields. */
final class OxBotKnowledge {
    record Fact(boolean agrees, int subjectLevel) {}
    private static final Pattern LEVEL = Pattern.compile("\\bLV\\.?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final class Holder { static final Map<Integer, Fact> facts = load(); }
    private OxBotKnowledge() {}

    static boolean choose(int question, int actorId, int actorLevel, double confidence) {
        return choose(question, actorId, actorLevel, confidence, Holder.facts.get(question));
    }
    static boolean choose(int question, int actorId, int actorLevel, double confidence, Fact fact) {
        Random belief = new Random(31L * question + actorId * 17L);
        boolean guess = belief.nextBoolean();
        if (fact == null) return guess;
        double familiarity = Math.min(1, (double) actorLevel / Math.max(1, fact.subjectLevel()));
        double recall = Math.min(.95, .15 + .65 * confidence + .2 * familiarity);
        if (belief.nextDouble() >= recall) return guess;
        // A recalled fact can still be misremembered; level and confidence never guarantee survival.
        return belief.nextDouble() < .06 ? !fact.agrees() : fact.agrees();
    }
    private static Map<Integer, Fact> load() {
        var names = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Mob.img");
        Map<String, Integer> ids = new HashMap<>();
        for (Data entry : names) {
            String name = normalize(DataTool.getString("name", entry, ""));
            if (name.isEmpty()) continue;
            try { ids.putIfAbsent(name, Integer.parseInt(entry.getName())); }
            catch (NumberFormatException ignored) { }
        }
        var source = DataProviderFactory.getDataProvider(WZFiles.MOB);
        Map<String, Integer> levels = new HashMap<>();
        Map<Integer, Fact> result = new HashMap<>();
        Data quiz = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("OXQuiz.img");
        for (Data group : quiz) for (Data entry : group) {
            String statement = DataTool.getString("q", entry, "");
            if (!LEVEL.matcher(statement).find()) continue;
            Fact fact = interpret(statement, ids.keySet(), name -> levels.computeIfAbsent(name, key -> {
                Data monster = source.getData(String.format(Locale.ROOT, "%07d.img", ids.get(key)));
                String link = DataTool.getString("info/link", monster, "");
                if (!link.isEmpty()) monster = source.getData(String.format(Locale.ROOT, "%07d.img", Integer.parseInt(link)));
                return DataTool.getInt("info/level", monster, -1);
            }));
            if (fact != null) try {
                result.put(Integer.parseInt(group.getName()) * 10000 + Integer.parseInt(entry.getName()), fact);
            } catch (NumberFormatException ignored) { }
        }
        return Map.copyOf(result);
    }
    static Fact interpret(String statement, Set<String> names, java.util.function.ToIntFunction<String> level) {
        var claimed = LEVEL.matcher(statement);
        if (!claimed.find()) return null;
        String normalized = normalize(statement).replaceFirst("^THE ", "");
        String subject = names.stream().filter(name -> normalized.startsWith(name + " "))
                .max(Comparator.comparingInt(String::length)).orElse(null);
        if (subject == null) return null;
        int actual = level.applyAsInt(subject);
        return actual < 1 ? null : new Fact(actual == Integer.parseInt(claimed.group(1)), actual);
    }
    private static String normalize(String text) {
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "").replaceAll("\\s+", " ").strip();
    }
}

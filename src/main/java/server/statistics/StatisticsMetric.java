package server.statistics;

/** Wire IDs are explicit and must never be reassigned. This is the core WS1 slice. */
public enum StatisticsMetric {
    USE_UNITS(1, "use.units_consumed", "USE items consumed", "item"),
    MONSTER_DEFEATS(2, "monster.defeats", "Monsters defeated", "monster"),
    QUEST_COMPLETIONS(3, "quest.completions", "Quests completed", "quest"),
    PLAYER_DEATHS(4, "player.deaths", "Adventurer deaths", "cause"),
    PQ_RUN_CLEARS(5, "pq.runs_cleared", "PQ runs cleared", "activity"),
    PQ_PARTICIPANT_CLEARS(6, "pq.participant_clears", "PQ participant completions", "activity"),
    JQ_FINISHES(7, "jq.finishes", "JQ finishes", "activity");

    public static final int COUNT = 7;
    private static final StatisticsMetric[] BY_ID = new StatisticsMetric[COUNT + 1];
    static { for (StatisticsMetric metric : values()) BY_ID[metric.id] = metric; }

    public final int id;
    public final String key;
    public final String title;
    public final String entityKind;

    StatisticsMetric(int id, String key, String title, String entityKind) {
        this.id = id; this.key = key; this.title = title; this.entityKind = entityKind;
    }
    public static boolean valid(int id) { return id > 0 && id <= COUNT; }
    public static StatisticsMetric byId(int id) {
        if (!valid(id)) throw new IllegalArgumentException("Unknown metric ID");
        return BY_ID[id];
    }
}

package server.statistics;

/** Stable primitive values; UNKNOWN is a real bucket, never a guessed attribution. */
public final class StatisticsDimensions {
    private StatisticsDimensions() {}
    public static final int HUMAN = 0, BOT = 1, MIXED = 2, SYSTEM = 3, UNKNOWN = 4;
    public static final int ORDINARY = 0, ASSISTED = 1, ASSISTANCE_UNKNOWN = 2;
    public static final int PURPOSE_UNKNOWN = 0, ACTIVATION = 1, AMMUNITION = 2,
            SKILL_REAGENT = 3, UPGRADE = 4, CRAFTING = 5, QUEST_SURRENDER = 6, EVENT_PAYMENT = 7;
    public static final int METHOD_UNKNOWN = 0, MANUAL = 1, PET = 2, BOT_METHOD = 3, TRAINER = 4, SCRIPT = 5;
    public static boolean valid(int world, int population, int assistance, int entity, int region,
                                int reason, int method, long timestamp, long amount) {
        return world >= 0 && population >= HUMAN && population <= UNKNOWN
                && assistance >= ORDINARY && assistance <= ASSISTANCE_UNKNOWN
                && entity >= 0 && region >= 0 && reason >= 0 && reason <= 255
                && method >= 0 && method <= 255 && timestamp >= 0 && amount > 0;
    }
}

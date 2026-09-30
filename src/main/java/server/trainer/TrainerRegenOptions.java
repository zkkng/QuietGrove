package server.trainer;

import java.util.Map;
import java.util.Set;

/** Bounded resource restoration; activation remains in the leased HP/MP switches. */
record TrainerRegenOptions(int hpPercent, int mpPercent, int interval) {
    static final TrainerRegenOptions DEFAULT = new TrainerRegenOptions(10, 10, 1000);
    TrainerRegenOptions {
        if (hpPercent < 1 || hpPercent > 25 || mpPercent < 1 || mpPercent > 25 || interval < 250 || interval > 5000)
            throw new IllegalArgumentException("Regen: 1..25 percent per tick, 250..5000 ms");
    }
    static TrainerRegenOptions parse(Map<String,String> f) {
        if (!f.keySet().equals(Set.of("hpPercent", "mpPercent", "interval")))
            throw new IllegalArgumentException("Incomplete or unknown regeneration settings");
        return new TrainerRegenOptions(Integer.parseInt(f.get("hpPercent")), Integer.parseInt(f.get("mpPercent")), Integer.parseInt(f.get("interval")));
    }
    static int amount(int maximum, int percent) { return maximum <= 0 ? 0 : (int)Math.max(1, (long)maximum * percent / 100); }
    void status(Map<String,String> r) {
        r.put("regenOptionsReady", "1"); r.put("hpPercent", Integer.toString(hpPercent));
        r.put("mpPercent", Integer.toString(mpPercent)); r.put("regenInterval", Integer.toString(interval));
    }
}

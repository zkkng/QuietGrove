package server.events.gm;

import java.util.List;
import java.util.random.RandomGenerator;

/** Clock schedules replenishment; only real deaths clear a wave. */
final class BossWaveBudget {
    static final int LIVE_LIMIT = 10;
    static final long PERIOD = 180_000;
    static final List<Integer> POOL = List.of(6130101, 6300005, 9400205, 8130100);
    private long due;
    BossWaveBudget(long now) { due = now + PERIOD; }
    static int initial(RandomGenerator random) { return random.nextInt(5, 11); }
    static int monster(RandomGenerator random) { return POOL.get(random.nextInt(POOL.size())); }
    synchronized int replenish(long now, int living, int queued, RandomGenerator random) {
        if (now < due) return 0;
        due = now + PERIOD;
        return Math.max(0, random.nextInt(7, 11) - living - queued);
    }
}

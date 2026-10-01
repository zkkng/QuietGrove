package server.events.gm;

import java.util.List;

/** A later wave requires actual victory; clocks cannot advance a surviving boss into a fake result. */
final class WaveSequence {
    record Wave(String encounter, int count, boolean boss) {
        Wave { if (count < 1 || count > (boss ? BossWaveBudget.LIVE_LIMIT : 30)) throw new IllegalArgumentException("Wave live limit"); }
    }
    record Token(long generation, int index, Wave wave) {}
    private final List<Wave> plan;
    private long generation, due;
    private int index;
    private boolean active, closed;
    WaveSequence(List<Wave> plan, long now) {
        if (plan.isEmpty() || plan.size() > 30) throw new IllegalArgumentException("Finite wave plan required");
        this.plan = List.copyOf(plan); due = now + 20_000;
    }
    synchronized Token next(long now) {
        if (closed || active || now < due) return null;
        active = true; return new Token(++generation, index, plan.get(index));
    }
    synchronized boolean complete(Token token, boolean victory, long now) {
        if (closed || !active || token.generation() != generation || token.index() != index) return false;
        active = false;
        if (!victory || ++index == plan.size()) closed = true;
        else due = now + (token.wave().boss() ? 0 : 30_000);
        return true;
    }
    synchronized void cancel() { closed = true; active = false; generation++; }
    synchronized boolean closed() { return closed; }
    synchronized int completed() { return index; }
    int total() { return plan.size(); }
    static List<Wave> bosses() {
        var random=java.util.concurrent.ThreadLocalRandom.current();
        return java.util.stream.IntStream.range(0,3)
                .mapToObj(i->new Wave("wave-bosses",BossWaveBudget.initial(random),true)).toList();
    }
    static List<Wave> mobs() { return List.of(new Wave("snail", 30, false)); }
}

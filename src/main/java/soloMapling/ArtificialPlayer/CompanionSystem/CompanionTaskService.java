package soloMapling.ArtificialPlayer.CompanionSystem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** In-memory exclusive ownership. No Character references, scheduler closures or restart persistence. */
public final class CompanionTaskService {
    public enum State { FOLLOW, ENGAGE, SUPPORT, LEADER_GRACE, RECOVER_ROUTE, DEAD, RELEASING }
    public enum Objective { TRAINING, FIELD_BOSS, RESTING }
    public record PartyKey(int worldId, int partyId) {}
    public record PriorActivity(String botType, int homeMapId, int destinationMapId) {}
    public record Reservation(long generation, long requestId, int botId, PartyKey party,
                              int ownerId, int channelId, long expiresAtMs, PriorActivity prior) {}
    public record Task(long generation, long requestId, int botId, PartyKey party, int ownerId,
                       int leaderId, int channelId, State state, Objective objective, PriorActivity prior) {}
    public enum EventRole { PARTICIPANT, SPECTATOR, HOST }
    public record EventLease(long generation, String eventId, int botId, int worldId, int channelId,
                             EventRole role, long expiresAtMs, boolean committed, PriorActivity prior) {}

    private final Map<Integer, Reservation> reservations = new HashMap<>();
    private final Map<Integer, Task> tasks = new HashMap<>();
    // Events use the SAME exclusive owner monitor, but never consume the companion-only cap.
    private final Map<Integer, EventLease> eventLeases = new HashMap<>();
    private final LongSupplier clock;
    private int globalCap;
    private long nextGeneration;

    public CompanionTaskService(LongSupplier clock, int globalCap) {
        if (clock == null || globalCap < 1) throw new IllegalArgumentException("clock/cap");
        this.clock = clock;
        this.globalCap = globalCap;
    }

    public synchronized Optional<Reservation> reserve(long requestId, int botId, PartyKey party,
                                                      int ownerId, int channelId, int actualMemberCount,
                                                      long expiresAtMs, PriorActivity prior) {
        expire();
        if (party == null || prior == null || requestId <= 0 || botId <= 0 || ownerId <= 0
                || channelId <= 0 || actualMemberCount < 1 || actualMemberCount >= 6
                || expiresAtMs <= clock.getAsLong() || reservations.containsKey(botId)
                || tasks.containsKey(botId) || eventLeases.containsKey(botId)
                || reservations.size() + tasks.size() >= globalCap
                || actualMemberCount + pendingSeats(party) >= 6) return Optional.empty();
        Reservation reservation = new Reservation(++nextGeneration, requestId, botId, party,
                ownerId, channelId, expiresAtMs, prior);
        reservations.put(botId, reservation);
        return Optional.of(reservation);
    }

    /**
     * Adapter must revalidate live eligibility/owner/map and perform a canonical capacity-safe join.
     * The callback may not speak or recursively mutate this service. Cancellation linearizes with
     * commit under this monitor; the task exists only after the canonical join succeeds.
     */
    public synchronized Optional<Task> commit(Reservation token, BooleanSupplier canonicalJoin) {
        expire();
        if (token == null || canonicalJoin == null || !token.equals(reservations.get(token.botId())))
            return Optional.empty();
        try {
            if (!canonicalJoin.getAsBoolean()) return Optional.empty();
            Task task = new Task(token.generation(), token.requestId(), token.botId(), token.party(),
                    token.ownerId(), token.ownerId(), token.channelId(), State.FOLLOW,
                    Objective.TRAINING, token.prior());
            tasks.put(task.botId(), task);
            return Optional.of(task);
        } finally {
            reservations.remove(token.botId(), token);
        }
    }

    public synchronized void cancelRequest(long requestId) {
        reservations.values().removeIf(r -> r.requestId() == requestId);
    }

    public synchronized void cancelReservation(Reservation token) {
        if (token != null) reservations.remove(token.botId(), token);
    }

    /** Return the descriptor exactly once; caller performs canonical leave and safe restoration. */
    public synchronized Optional<Task> release(int botId, long generation) {
        Task task = tasks.get(botId);
        if (task == null || task.generation() != generation) return Optional.empty();
        tasks.remove(botId);
        return Optional.of(task);
    }

    public synchronized Optional<Task> transition(int botId, long generation, int humanLeaderId, State state) {
        Task task = tasks.get(botId);
        if (task == null || task.generation() != generation || humanLeaderId <= 0 || state == null
                || state == State.RELEASING) return Optional.empty();
        Task updated = new Task(task.generation(), task.requestId(), task.botId(), task.party(),
                task.ownerId(), humanLeaderId, task.channelId(), state, task.objective(), task.prior());
        tasks.put(botId, updated);
        return Optional.of(updated);
    }

    public synchronized Optional<Task> task(int botId) { return Optional.ofNullable(tasks.get(botId)); }
    public synchronized Optional<EventLease> eventLease(int botId) { return Optional.ofNullable(eventLeases.get(botId)); }
    public synchronized List<EventLease> eventLeases() { return List.copyOf(eventLeases.values()); }
    public synchronized Optional<Task> objective(int botId, long generation, Objective objective) {
        Task t = tasks.get(botId);
        if (t == null || t.generation() != generation || objective == null) return Optional.empty();
        Task updated = new Task(t.generation(), t.requestId(), t.botId(), t.party(), t.ownerId(),
                t.leaderId(), t.channelId(), t.state(), objective, t.prior());
        tasks.put(botId, updated); return Optional.of(updated);
    }
    public synchronized List<Task> tasks() { return List.copyOf(tasks.values()); }
    public synchronized boolean owned(int botId) {
        expire();
        return tasks.containsKey(botId) || reservations.containsKey(botId) || eventLeases.containsKey(botId);
    }
    /** Selection rejects other leases; callback revalidation may retain its own pending reservation. */
    public synchronized boolean ownedByOther(int botId, PartyKey party, int ownerId) {
        expire();
        Reservation pending = reservations.get(botId);
        return tasks.containsKey(botId) || eventLeases.containsKey(botId)
                || pending != null && (!pending.party().equals(party) || pending.ownerId() != ownerId);
    }
    public synchronized Optional<EventLease> reserveEvent(String eventId, int botId, int worldId,
            int channelId, EventRole role, long expiresAtMs, PriorActivity prior) {
        return reserveEvent(eventId,botId,worldId,channelId,role,expiresAtMs,prior,Integer.MAX_VALUE);
    }
    /** Reservations and committed actors share one atomic world budget across all events. */
    public synchronized Optional<EventLease> reserveEvent(String eventId, int botId, int worldId,
            int channelId, EventRole role, long expiresAtMs, PriorActivity prior, int worldBotLimit) {
        expire();
        if (eventId == null || eventId.isBlank() || botId <= 0 || channelId <= 0 || role == null
                || prior == null || worldBotLimit <= 0 || expiresAtMs <= clock.getAsLong() || owned(botId)
                || eventLeases.values().stream().filter(e -> e.worldId() == worldId).count() >= worldBotLimit) return Optional.empty();
        EventLease lease = new EventLease(++nextGeneration, eventId, botId, worldId, channelId,
                role, expiresAtMs, false, prior);
        eventLeases.put(botId, lease);
        return Optional.of(lease);
    }
    public synchronized Optional<EventLease> commitEvent(EventLease token) {
        expire();
        if (token == null || token.committed() || !token.equals(eventLeases.get(token.botId())))
            return Optional.empty();
        EventLease active = new EventLease(token.generation(), token.eventId(), token.botId(),
                token.worldId(), token.channelId(), token.role(), Long.MAX_VALUE, true, token.prior());
        eventLeases.put(token.botId(), active);
        return Optional.of(active);
    }
    public synchronized Optional<EventLease> releaseEvent(int botId, long generation) {
        EventLease lease = eventLeases.get(botId);
        if (lease == null || lease.generation() != generation) return Optional.empty();
        eventLeases.remove(botId);
        return Optional.of(lease);
    }
    public synchronized int pendingSeats(PartyKey party) {
        expire();
        return (int) reservations.values().stream().filter(r -> r.party().equals(party)).count();
    }
    public synchronized int occupiedCapacity() { expire(); return reservations.size() + tasks.size(); }
    /** Lowering the cap never evicts existing owners; it only blocks further reservations. */
    public synchronized void configureGlobalCap(int cap) {
        if (cap < 1) throw new IllegalArgumentException("companion cap");
        globalCap = cap;
    }
    public synchronized void expire() {
        long now = clock.getAsLong();
        reservations.values().removeIf(r -> now >= r.expiresAtMs());
        eventLeases.values().removeIf(r -> !r.committed() && now >= r.expiresAtMs());
    }

    private static final class Shared {
        static final CompanionTaskService INSTANCE = new CompanionTaskService(System::currentTimeMillis, 30);
    }
    /** All future runtime companion recruitment and event attendance must use this one registry. */
    public static CompanionTaskService shared() { return Shared.INSTANCE; }
}

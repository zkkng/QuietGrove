package server.events.gm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Authoritative roster/result identity. Mutation is short and never performs I/O or pathfinding. */
public final class EventSession {
    public enum Phase { DRAFT, PREPARING, REGISTRATION, COUNTDOWN, RUNNING, SETTLING, CLOSED, CANCELLED, FAILED }
    public enum Attendance { PENDING, ACTIVE, SPECTATOR, ELIMINATED, WINNER, LEFT }
    public enum Claim { NONE, IN_FLIGHT, GRANTED }
    public record Capacity(int activeLimit, int visibleLimit, int pendingLimit, int humanReserve,
                           String measuredProfileId, boolean botsVerified) {
        public Capacity {
            if (activeLimit < 1 || visibleLimit < activeLimit || pendingLimit < 1 || humanReserve < 0
                    || humanReserve > activeLimit || measuredProfileId == null
                    || (botsVerified && measuredProfileId.isBlank())) throw new IllegalArgumentException("capacity profile");
        }
        public static Capacity development(int operatorLimit) {
            return new Capacity(operatorLimit, operatorLimit, operatorLimit, 0, "UNMEASURED-development", false);
        }
    }
    public record Admission(UUID eventId, long generation, int actorId, boolean bot, int team,
                            int returnMapId, long expiresAtMs, boolean spectator) {}
    public record Entrant(Admission admission, Attendance attendance, Claim claim) {}
    public record Snapshot(UUID id, long generation, String key, Phase phase, boolean entryOpen,
                           int humans, int bots, int pending, int spectators, int winners, long deadlineMs, Capacity capacity) {}

    private final UUID id = UUID.randomUUID();
    private final long generation;
    private final EventDefinition definition;
    private final Capacity capacity;
    private final LongSupplier clock;
    private final int worldId, channelId;
    private int hostId;
    private final Map<Integer, Entrant> roster = new HashMap<>();
    private Phase phase = Phase.DRAFT;
    private boolean entryOpen;
    private long deadlineMs;
    private boolean botTrial;
    private int hostVisible;

    public EventSession(long generation, EventDefinition definition, Capacity capacity, LongSupplier clock,
                        int worldId, int channelId, int hostId) {
        if (generation <= 0 || definition == null || capacity == null || clock == null
                || channelId <= 0 || hostId <= 0) throw new IllegalArgumentException("event identity");
        this.generation = generation; this.definition = definition; this.capacity = capacity;
        this.clock = clock; this.worldId = worldId; this.channelId = channelId; this.hostId = hostId;
    }
    public synchronized boolean open() {
        if (phase != Phase.DRAFT || !definition.available()) return false;
        phase = Phase.PREPARING;
        phase = Phase.REGISTRATION;
        entryOpen = true;
        return true;
    }
    public synchronized Optional<Admission> reserve(int actorId, boolean bot, int returnMapId) {
        return reserve(actorId,bot,returnMapId,false);
    }
    public synchronized Optional<Admission> reserve(int actorId, boolean bot, int returnMapId, boolean spectator) {
        expireAdmissions();
        if (!entryOpen || phase != Phase.REGISTRATION || actorId <= 0 || actorId == hostId
                || returnMapId < 0 || roster.containsKey(actorId) || (bot && !capacity.botsVerified() && !botTrial))
            return Optional.empty();
        List<Entrant> occupied = roster.values().stream()
                .filter(e -> e.attendance() == Attendance.PENDING || e.attendance() == Attendance.ACTIVE || e.attendance()==Attendance.SPECTATOR).toList();
        List<Entrant> competitors=occupied.stream().filter(e->!e.admission().spectator()).toList();
        long pending = occupied.stream().filter(e -> e.attendance() == Attendance.PENDING).count();
        long bots = competitors.stream().filter(e -> e.admission().bot()).count();
        if ((!spectator && competitors.size() >= capacity.activeLimit()) || occupied.size()+hostVisible >= capacity.visibleLimit()
                || pending >= capacity.pendingLimit()
                || (!spectator && bot && bots >= capacity.activeLimit() - capacity.humanReserve())) return Optional.empty();
        int zero = (int) competitors.stream().filter(e -> e.admission().team() == 0).count();
        int team = spectator?-1:zero * 2 <= competitors.size() ? 0 : 1;
        Admission admission = new Admission(id, generation, actorId, bot, team, returnMapId,
                clock.getAsLong() + 15_000,spectator);
        roster.put(actorId, new Entrant(admission, Attendance.PENDING, Claim.NONE));
        return Optional.of(admission);
    }
    public synchronized boolean commit(Admission token) {
        expireAdmissions();
        Entrant entrant = token == null ? null : roster.get(token.actorId());
        if (entrant == null || entrant.attendance() != Attendance.PENDING
                || !entrant.admission().equals(token) || phase != Phase.REGISTRATION) return false;
        roster.put(token.actorId(), new Entrant(token, token.spectator()?Attendance.SPECTATOR:Attendance.ACTIVE, Claim.NONE));
        return true;
    }
    public synchronized Optional<Entrant> leave(int actorId) {
        return Optional.ofNullable(roster.remove(actorId));
    }
    public synchronized void closeEntry() { entryOpen = false; }
    public synchronized boolean enableBotTrial() {
        if(phase!=Phase.REGISTRATION) return false;botTrial=true;return true;
    }
    public synchronized boolean botTrial() {return botTrial;}
    public synchronized void hostVisible(boolean visible) {hostVisible=visible?1:0;}
    public synchronized boolean reopen() { if(phase!=Phase.REGISTRATION) return false; entryOpen=true;return true; }
    public synchronized boolean countdown() {
        if(phase!=Phase.REGISTRATION || roster.values().stream().noneMatch(e->e.attendance()==Attendance.ACTIVE)) return false;
        entryOpen=false; phase=Phase.COUNTDOWN; return true;
    }
    public synchronized boolean start() {
        if ((phase != Phase.REGISTRATION && phase!=Phase.COUNTDOWN) || roster.values().stream().noneMatch(e -> e.attendance() == Attendance.ACTIVE))
            return false;
        entryOpen = false;
        roster.values().removeIf(e -> e.attendance() == Attendance.PENDING);
        phase = Phase.COUNTDOWN;
        deadlineMs = clock.getAsLong() + definition.rounds() * definition.roundMillis();
        phase = Phase.RUNNING;
        return true;
    }
    public synchronized boolean active(int actorId) {
        Entrant entrant = roster.get(actorId);
        return phase == Phase.RUNNING && entrant != null && entrant.attendance() == Attendance.ACTIVE;
    }
    public synchronized boolean extendDeadline(long expectedGeneration,long millis) {
        if(phase!=Phase.RUNNING || generation!=expectedGeneration || millis<=0) return false;
        deadlineMs=Math.addExact(deadlineMs,millis);return true;
    }
    public synchronized boolean eliminate(int actorId) {
        if (!active(actorId)) return false;
        Entrant entrant = roster.get(actorId);
        roster.put(actorId, new Entrant(entrant.admission(), Attendance.ELIMINATED, Claim.NONE));
        return true;
    }
    /** Courses commit each finish independently; timeout/cancellation cannot invent or revoke it. */
    public synchronized boolean finishActor(int actorId) {
        if (!active(actorId) || clock.getAsLong() >= deadlineMs) return false;
        Entrant entrant = roster.get(actorId);
        roster.put(actorId, new Entrant(entrant.admission(), Attendance.WINNER, Claim.NONE));
        return true;
    }
    public synchronized boolean transferHost(int expectedHostId, int newHostId) {
        if (terminal() || hostId != expectedHostId || newHostId <= 0 || roster.containsKey(newHostId)) return false;
        hostId = newHostId;
        return true;
    }
    public synchronized boolean settle(Set<Integer> winnerIds) {
        if (phase != Phase.RUNNING) return false;
        phase = Phase.SETTLING;
        for (var entry : new ArrayList<>(roster.entrySet())) {
            Entrant entrant = entry.getValue();
            if (entrant.attendance() == Attendance.ACTIVE) roster.put(entry.getKey(), new Entrant(
                    entrant.admission(), winnerIds.contains(entry.getKey()) ? Attendance.WINNER : Attendance.ELIMINATED, Claim.NONE));
        }
        phase = Phase.CLOSED;
        return true;
    }
    /** Result entitlement is immutable once settled. A late cancel cannot revoke a committed prize. */
    public synchronized List<Entrant> cancel() {
        return cancelOnce().orElse(List.of());
    }
    public synchronized Optional<List<Entrant>> cancelOnce() {
        if (terminal()) return Optional.empty();
        phase = Phase.CANCELLED; entryOpen = false;
        List<Entrant> removed = roster.values().stream().filter(e -> e.attendance() != Attendance.WINNER).toList();
        roster.values().removeIf(e -> e.attendance() != Attendance.WINNER);
        return Optional.of(removed);
    }
    public synchronized boolean beginClaim(int actorId) {
        Entrant entrant = roster.get(actorId);
        if (entrant == null || entrant.admission().bot()
                || entrant.attendance() != Attendance.WINNER || entrant.claim() != Claim.NONE) return false;
        roster.put(actorId, new Entrant(entrant.admission(), entrant.attendance(), Claim.IN_FLIGHT));
        return true;
    }
    public synchronized boolean completeClaim(int actorId, boolean delivered) {
        Entrant entrant = roster.get(actorId);
        if (entrant == null || entrant.claim() != Claim.IN_FLIGHT) return false;
        roster.put(actorId, new Entrant(entrant.admission(), entrant.attendance(), delivered ? Claim.GRANTED : Claim.NONE));
        return true;
    }
    public synchronized boolean terminal() { return phase == Phase.CLOSED || phase == Phase.CANCELLED || phase == Phase.FAILED; }
    public synchronized boolean running(long expectedGeneration) { return generation == expectedGeneration && phase == Phase.RUNNING; }
    public synchronized Optional<Entrant> entrant(int actorId) { return Optional.ofNullable(roster.get(actorId)); }
    public synchronized List<Entrant> entrants() { return List.copyOf(roster.values()); }
    public synchronized int freeSeats() {
        expireAdmissions();
        long competitors=roster.values().stream().filter(e->!e.admission().spectator()
                && (e.attendance()==Attendance.ACTIVE || e.attendance()==Attendance.PENDING)).count();
        return entryOpen ? Math.max(0, capacity.activeLimit() - (int)competitors) : 0;
    }
    public synchronized Snapshot snapshot() {
        expireAdmissions();
        int humans=0, bots=0, pending=0, spectators=0, winners=0;
        for (Entrant e : roster.values()) {
            if (e.attendance() == Attendance.PENDING) pending++;
            else if(e.attendance()==Attendance.SPECTATOR) spectators++;
            else if (e.attendance() == Attendance.ACTIVE || e.attendance() == Attendance.WINNER) {
                if (e.admission().bot()) bots++; else humans++;
            }
            if (e.attendance() == Attendance.WINNER) winners++;
        }
        return new Snapshot(id, generation, definition.key(), phase, entryOpen, humans, bots, pending, spectators, winners, deadlineMs, capacity);
    }
    private void expireAdmissions() {
        long now = clock.getAsLong();
        roster.values().removeIf(e -> e.attendance() == Attendance.PENDING && now >= e.admission().expiresAtMs());
    }
    public UUID id() { return id; }
    public long generation() { return generation; }
    public EventDefinition definition() { return definition; }
    public int worldId() { return worldId; }
    public int channelId() { return channelId; }
    public synchronized int hostId() { return hostId; }
}

package soloMapling.ArtificialPlayer.CompanionSystem;

import java.util.*;

/** Pure encounter state machine. Every callback carries exact channel/map/root/generation identity. */
public final class BossObjective {
    public enum Mode { HELP_CURRENT_ENCOUNTER, HUNT_TEMPLATE_IN_AREA, CONTINUING_PARTY }
    public enum Outcome { KILLED_WITH_CONTRIBUTION, RESOLVED_BY_OTHERS, TARGET_DESPAWNED, TARGET_ABSENT,
        PLAYER_CANCELLED, RETREATED, FAILED_ACCESS, TIMEOUT, FAILED_INFRASTRUCTURE }
    public record Identity(int world, int channel, long mapInstance, long encounter) {}
    public record Actor(int id, long generation) {}
    private final long generation, deadline;
    private final int ownerId;
    private int leaderId, gatherMap;
    private final CompanionTaskService.PartyKey party;
    private final BossDefinition definition;
    private final Mode mode;
    private final Set<Actor> actors = new LinkedHashSet<>();
    private final Set<Integer> completedPhases = new HashSet<>();
    private final Map<Integer, Long> damage = new HashMap<>();
    private Identity identity;
    private Outcome outcome;
    private boolean partyContributed;
    private String reason = "";
    public BossObjective(long generation, int ownerId, CompanionTaskService.PartyKey party, int gatherMap,
            BossDefinition definition, Mode mode, long now) {
        if (generation <= 0 || ownerId <= 0 || party == null || definition == null || mode == null) throw new IllegalArgumentException();
        this.generation = generation; this.ownerId = ownerId; this.leaderId = ownerId; this.party = party;
        this.gatherMap = gatherMap; this.definition = definition; this.mode = mode; deadline = now + definition.deadlineMs();
    }
    public synchronized boolean attach(int botId, long leaseGeneration) {
        return outcome == null && botId > 0 && leaseGeneration > 0 && actors.add(new Actor(botId, leaseGeneration));
    }
    public synchronized boolean observe(Identity candidate, int template) {
        return observe(candidate,template,0);
    }
    /** Canonical provenance permits joining a later phase after its original root has died. */
    public synchronized boolean observe(Identity candidate,int template,int canonicalRootTemplate) {
        if (outcome != null || candidate == null || candidate.world() != party.worldId() || candidate.channel() <= 0
                || candidate.mapInstance() <= 0 || candidate.encounter() <= 0 || !definition.combatTemplate(template)) return false;
        if (identity == null && (definition.roots().contains(template) || definition.roots().contains(canonicalRootTemplate))) identity = candidate;
        return candidate.equals(identity);
    }
    public synchronized void damage(Identity candidate, int template, int attacker, long actual) {
        if (actual > 0 && observe(candidate, template)) damage.merge(attacker, actual, Long::sum);
    }
    public synchronized void partyDamage(Identity candidate, int template, int attacker, long actual) {
        if (actual > 0 && observe(candidate,template)) { damage(candidate,template,attacker,actual); partyContributed = true; }
    }
    public synchronized boolean removed(Identity candidate, int template, boolean legitimateDeath) {
        if (outcome != null || candidate == null || !candidate.equals(identity) || !definition.combatTemplate(template)) return false;
        if (!legitimateDeath) return finish(Outcome.TARGET_DESPAWNED, "The target was removed without a kill.");
        completedPhases.add(template);
        if (definition.finals().contains(template)) {
            boolean contributed = partyContributed || damage.getOrDefault(ownerId, 0L) > 0 || damage.getOrDefault(leaderId, 0L) > 0
                    || actors.stream().anyMatch(a -> damage.getOrDefault(a.id(), 0L) > 0);
            return finish(contributed ? Outcome.KILLED_WITH_CONTRIBUTION : Outcome.RESOLVED_BY_OTHERS,
                    contributed ? "The party contributed to the kill." : "Another party resolved the target.");
        }
        return false;
    }
    public synchronized boolean finish(Outcome value, String detail) {
        if (outcome != null) return false;
        outcome = Objects.requireNonNull(value); reason = Objects.requireNonNull(detail); return true;
    }
    public synchronized boolean command(int humanId, Integer newGather, Integer newLeader) {
        if (outcome != null || humanId != ownerId && humanId != leaderId) return false;
        if (newGather != null) gatherMap = newGather;
        if (newLeader != null && newLeader > 0) leaderId = newLeader;
        return true;
    }
    public long generation() { return generation; }
    public int ownerId() { return ownerId; }
    public CompanionTaskService.PartyKey party() { return party; }
    public BossDefinition definition() { return definition; }
    public Mode mode() { return mode; }
    public synchronized int leaderId() { return leaderId; }
    public synchronized int gatherMap() { return gatherMap; }
    public synchronized Identity identity() { return identity; }
    public synchronized Outcome outcome() { return outcome; }
    public synchronized String reason() { return reason; }
    public synchronized Set<Actor> actors() { return Set.copyOf(actors); }
    public synchronized Map<Integer,Long> contributions() { return Map.copyOf(damage); }
    public long deadline() { return deadline; }
}

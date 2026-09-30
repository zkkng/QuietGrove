package soloMapling.ArtificialPlayer.CompanionSystem;

import java.util.ArrayList;
import java.util.List;

/** Pre-modifier EXP contract only. Monster remains the owner of rounding, rates and Holy Symbol. */
public final class CompanionExpPolicy {
    public record Member(int id, int level, boolean human, boolean companion, boolean active,
                         boolean normallyEligible, boolean mvp) {}
    public record Share(int id, double base, double partyBonus) {}
    public record Plan(List<Share> shares, int eligibleMembers, int activeMembers,
                       boolean enhanced, boolean hasPartySharers) {}
    public record Settings(double commonShare, double mvpShare, List<Double> bonusCurve) {
        public Settings {
            if (!Double.isFinite(commonShare) || !Double.isFinite(mvpShare)
                    || commonShare < 0 || mvpShare < 0 || Math.abs(commonShare + mvpShare - 1) > 1e-9
                    || bonusCurve == null || bonusCurve.size() != 6
                    || bonusCurve.stream().anyMatch(v -> v == null || !Double.isFinite(v) || v < 0)
                    || bonusCurve.getFirst() != 0) throw new IllegalArgumentException("EXP settings");
            bonusCurve = List.copyOf(bonusCurve);
        }
        public static Settings defaults() {
            return new Settings(0.8, 0.2, List.of(0.0, 0.20, 0.35, 0.45, 0.55, 0.65));
        }
    }

    private CompanionExpPolicy() {}

    /**
     * normallyEligible includes living/channel/map/level checks from the authoritative kill
     * snapshot. The adapter must select at most one MVP from retained recipients AFTER companion
     * filtering, preserving the engine's damage-ownership selection; stale MVP flags are rejected.
     * This helper deliberately does not apply HS, character/family/cafe rates, flat buffs or round.
     */
    public static Plan calculate(double partyExp, List<Member> snapshot, Settings settings) {
        if (!Double.isFinite(partyExp) || partyExp < 0 || snapshot == null || settings == null
                || snapshot.size() > 6 || snapshot.stream().anyMatch(m -> m == null || m.level() <= 0)
                || snapshot.stream().map(Member::id).distinct().count() != snapshot.size())
            throw new IllegalArgumentException("EXP snapshot");
        boolean companionParty = snapshot.stream().anyMatch(Member::companion);
        List<Member> eligible = snapshot.stream().filter(Member::normallyEligible)
                .filter(m -> !m.companion() || m.active()).toList();
        if (eligible.stream().filter(Member::mvp).count() > 1
                || snapshot.stream().anyMatch(m -> m.mvp() && !eligible.contains(m)))
            throw new IllegalArgumentException("MVP must be selected after eligibility filtering");
        int active = (int) eligible.stream().filter(Member::active).count();
        boolean enhanced = companionParty && active >= 2
                && eligible.stream().anyMatch(m -> m.human() && m.active());
        long totalLevels = eligible.stream().mapToLong(Member::level).sum();
        double ordinaryCoefficient = eligible.size() > 1 ? 0.05 * eligible.size() : 0;
        List<Share> shares = new ArrayList<>();
        for (Member member : eligible) {
            double base = partyExp * (settings.commonShare() * member.level() / totalLevels
                    + (member.mvp() ? settings.mvpShare() : 0));
            // Preserve ordinary entitlement for inactive humans; they do not enlarge enhanced count.
            double coefficient = enhanced && member.active()
                    ? settings.bonusCurve().get(active - 1) : ordinaryCoefficient;
            shares.add(new Share(member.id(), base, base * coefficient));
        }
        return new Plan(List.copyOf(shares), eligible.size(), active, enhanced, eligible.size() > 1);
    }
}

package soloMapling.ArtificialPlayer.CompanionSystem;

import java.util.Set;

/** Caller supplies a fresh authoritative snapshot, both at selection and delayed acceptance. */
public final class CompanionEligibility {
    private static final Set<String> TYPES = Set.of("SocialBot", "TrainingBot", "TownWandererBot", "HybridPilotBot");
    public record Candidate(int botId, String type, boolean registered, boolean verifiedCombatBuild,
                            boolean alive, int worldId, int channelId, long mapInstanceId,
                            boolean visible, boolean busy, boolean eventInstance, boolean blocked,
                            int conversationOwnerId, boolean partied, boolean owned) {}
    public record Requester(int humanId, int worldId, int channelId, long mapInstanceId) {}

    private CompanionEligibility() {}

    public static boolean eligible(Candidate bot, Requester human) {
        return bot != null && human != null && bot.botId() > 0 && human.humanId() > 0
                && bot.registered() && TYPES.contains(bot.type()) && bot.verifiedCombatBuild()
                && bot.alive() && bot.visible() && !bot.busy() && !bot.eventInstance()
                && !bot.blocked() && !bot.partied() && !bot.owned()
                && (bot.conversationOwnerId() <= 0 || bot.conversationOwnerId() == human.humanId())
                && bot.worldId() == human.worldId() && bot.channelId() == human.channelId()
                && bot.mapInstanceId() == human.mapInstanceId();
    }
}

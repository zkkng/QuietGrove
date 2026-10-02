package soloMapling.ArtificialPlayer.CompanionSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CompanionEligibilityTest {
    private final CompanionEligibility.Requester human = new CompanionEligibility.Requester(1, 0, 1, 100);
    private CompanionEligibility.Candidate bot(String type, boolean verified, boolean alive, int world,
            int channel, long map, boolean visible, boolean busy, boolean event, boolean blocked,
            int conversation, boolean partied, boolean owned) {
        return new CompanionEligibility.Candidate(10, type, true, verified, alive, world, channel, map,
                visible, busy, event, blocked, conversation, partied, owned);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SocialBot", "TrainingBot", "TownWandererBot", "HybridPilotBot"})
    void allowlistRequiresVerifiedBuild(String type) {
        assertTrue(CompanionEligibility.eligible(bot(type, true, true, 0, 1, 100, true, false,
                false, false, 1, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot(type, false, true, 0, 1, 100, true, false,
                false, false, 1, false, false), human));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FMBot", "NXMerchantBot", "SellingMerchantBot", "BuyingMerchantBot", "ScrollingBot",
            "DiceBot", "BlackjackDealerBot", "GachaBot", "TutorialBot", "GameZoneHostBot", "HenesysJQBot",
            "OPQBot", "FollowerBot", "TestAttackBot"})
    void unavailableRoles(String type) {
        assertFalse(CompanionEligibility.eligible(bot(type, true, true, 0, 1, 100, true, false,
                false, false, -1, false, false), human));
    }

    @Test
    void eachLiveRestrictionIsIndependentIncludingSameMapIdDifferentChannel() {
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, false, 0, 1, 100, true, false, false, false, 0, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 1, 1, 100, true, false, false, false, 0, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 0, 2, 100, true, false, false, false, 0, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 0, 1, 101, true, false, false, false, 0, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 0, 1, 100, false, false, false, false, 0, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 0, 1, 100, true, true, false, false, 0, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 0, 1, 100, true, false, true, false, 0, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 0, 1, 100, true, false, false, true, 0, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 0, 1, 100, true, false, false, false, 2, false, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 0, 1, 100, true, false, false, false, 0, true, false), human));
        assertFalse(CompanionEligibility.eligible(bot("SocialBot", true, true, 0, 1, 100, true, false, false, false, 0, false, true), human));
        assertFalse(CompanionEligibility.eligible(new CompanionEligibility.Candidate(10, "SocialBot", false,
                true, true, 0, 1, 100, true, false, false, false, 0, false, false), human));
    }
}

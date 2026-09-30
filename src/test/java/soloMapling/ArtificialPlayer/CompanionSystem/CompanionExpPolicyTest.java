package soloMapling.ArtificialPlayer.CompanionSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.CompanionSystem.CompanionExpPolicy.*;

class CompanionExpPolicyTest {
    private final Settings settings = Settings.defaults();

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void exactEqualLevelVectorsUseReplacementCurveWithoutRatesOrHolySymbol(int count) {
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < count; i++) members.add(new Member(i + 1, 100, i == 0, i != 0, true, true, i == 0));
        var plan = calculate(1000, members, settings);
        double coefficient = settings.bonusCurve().get(count - 1);
        assertEquals(count > 1, plan.enhanced());
        assertEquals(count > 1, plan.hasPartySharers());
        assertEquals(1000 * (0.8 / count + 0.2), plan.shares().getFirst().base(), 1e-9);
        assertEquals(plan.shares().getFirst().base() * coefficient, plan.shares().getFirst().partyBonus(), 1e-9);
        assertEquals(1000 * (1 + coefficient), plan.shares().stream()
                .mapToDouble(s -> s.base() + s.partyBonus()).sum(), 1e-9);
        if (count == 6) {
            assertEquals(550, plan.shares().getFirst().base() + plan.shares().getFirst().partyBonus(), 1e-9);
            assertEquals(220, plan.shares().get(1).base() + plan.shares().get(1).partyBonus(), 1e-9);
        }
    }

    @Test
    void parkedBotsDoNotDiluteHumanOrEnableFullHolySymbolOrBonus() {
        List<Member> members = new ArrayList<>();
        members.add(new Member(1, 50, true, false, true, true, true));
        for (int i = 2; i <= 6; i++) members.add(new Member(i, 200, false, true, false, true, false));
        var plan = calculate(1000, members, settings);
        assertEquals(1, plan.eligibleMembers());
        assertFalse(plan.enhanced());
        assertFalse(plan.hasPartySharers());
        assertEquals(List.of(new Share(1, 1000, 0)), plan.shares());
    }

    @Test
    void inactiveHumansRetainOrdinaryEntitlementWithoutIncreasingEnhancedCount() {
        var plan = calculate(1000, List.of(new Member(1, 50, true, false, true, true, true),
                new Member(2, 100, false, true, true, true, false),
                new Member(3, 50, true, false, false, true, false)), settings);
        assertEquals(2, plan.activeMembers());
        assertEquals(400, plan.shares().getFirst().base(), 1e-9);
        assertEquals(80, plan.shares().getFirst().partyBonus(), 1e-9);
        assertEquals(80, plan.shares().get(1).partyBonus(), 1e-9);
        assertEquals(30, plan.shares().get(2).partyBonus(), 1e-9);
    }

    @Test
    void allHumanPartiesPreserveOrdinarySharingAndOffMapDeadLevelIneligibleAreExcluded() {
        var plan = calculate(1000, List.of(new Member(1, 50, true, false, true, true, true),
                new Member(2, 50, true, false, false, true, false),
                new Member(3, 200, true, false, true, false, false)), settings);
        assertFalse(plan.enhanced());
        assertEquals(2, plan.eligibleMembers());
        assertEquals(600, plan.shares().getFirst().base(), 1e-9);
        assertEquals(60, plan.shares().getFirst().partyBonus(), 1e-9);
        assertEquals(new Share(2, 400, 40), plan.shares().get(1));
    }

    @Test
    void activeBotsWithoutActiveHumanDoNotEnhanceAndSettingsAreConfigurable() {
        var members = List.of(new Member(1, 100, true, false, false, true, false),
                new Member(2, 100, false, true, true, true, true));
        var plan = calculate(1000, members, settings);
        assertFalse(plan.enhanced());
        assertEquals(60, plan.shares().get(1).partyBonus(), 1e-9);
        var custom = new Settings(0.5, 0.5, List.of(0.0, 0.3, 0.4, 0.5, 0.6, 0.7));
        var active = List.of(new Member(1, 100, true, false, true, true, true),
                new Member(2, 100, false, true, true, true, false));
        assertEquals(List.of(new Share(1, 750, 225), new Share(2, 250, 75)), calculate(1000, active, custom).shares());
    }

    @Test
    void levelSumDoesNotOverflowAndInvalidSnapshotsAreRejected() {
        var members = List.of(new Member(1, Integer.MAX_VALUE, true, false, true, true, true),
                new Member(2, Integer.MAX_VALUE, false, true, true, true, false));
        assertEquals(600, calculate(1000, members, settings).shares().getFirst().base(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> calculate(Double.NaN, members, settings));
        assertThrows(IllegalArgumentException.class, () -> calculate(-1, members, settings));
        assertThrows(IllegalArgumentException.class, () -> calculate(1000, List.of(members.getFirst(), members.getFirst()), settings));
        assertThrows(IllegalArgumentException.class, () -> calculate(1000, List.of(members.getFirst(),
                new Member(2, 100, false, true, true, true, true)), settings));
        assertThrows(IllegalArgumentException.class, () -> calculate(1000, List.of(members.getFirst(),
                new Member(2, 100, false, true, false, true, true)), settings));
        assertThrows(IllegalArgumentException.class, () -> new Settings(0.8, 0.8, settings.bonusCurve()));
        assertThrows(IllegalArgumentException.class, () -> new Settings(0.8, 0.2, List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6)));
    }
}

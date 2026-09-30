package server.pccafe;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class PcCafeStateTest {
    private final PcCafeConfig config = new PcCafeConfig(true, ZoneId.of("America/Los_Angeles"), 10, 50, 5, 20, .05, 1, 1.2, 1.2, 30);
    private PcCafeState state() {
        var state = new PcCafeState(); state.refresh(Instant.parse("2026-09-23T12:00:00Z"), config); return state;
    }
    @Test void killsFillGaugeAndRespectWeeklyCap() {
        var s = state();
        for (int i = 0; i < 19; i++) assertFalse(s.kill(config));
        assertTrue(s.kill(config)); assertEquals(1, s.balance());
        for (int i = 0; i < 1000; i++) s.kill(config);
        assertEquals(10, s.balance()); assertEquals(10, s.earned()); assertEquals(0, s.available(config));
    }
    @Test void welcomeIsOnceAcrossSaveLoadAndWeeklyReset() {
        var s = state(); assertEquals(5, s.welcome(config));
        s = PcCafeState.decode(s.encode());
        s.refresh(Instant.parse("2026-10-06T12:00:00Z"), config);
        assertEquals(0, s.welcome(config)); assertEquals(5, s.balance());
    }
    @Test void failedOrThrowingGrantSpendsNothingAndDoesNotUseLimit() {
        var s = state(); s.credit(10);
        assertFalse(s.purchase(2000004, 5, 1, () -> false));
        assertThrows(IllegalStateException.class, () -> s.purchase(2000004, 5, 1, () -> { throw new IllegalStateException(); }));
        assertEquals(10, s.balance()); assertEquals(0, s.purchased(2000004));
    }
    @Test void purchaseChecksFundsAndLimitsBeforeGrant() {
        var s = state(); s.credit(10);
        assertFalse(s.purchase(2000004, 11, 1, () -> fail("Cannot grant when poor")));
        assertTrue(s.purchase(2000004, 5, 1, () -> true));
        assertFalse(s.purchase(2000004, 5, 1, () -> fail("Cannot exceed weekly limit")));
        assertEquals(5, s.balance()); assertEquals(10, s.earned());
        s = PcCafeState.decode(s.encode()); assertEquals(1, s.purchased(2000004));
    }
    @Test void mondayResetUsesLocalCalendarAndNeverRewinds() {
        var s = state(); s.credit(10); s.purchase(2000004, 5, 1, () -> true);
        s.refresh(Instant.parse("2026-09-28T06:59:59Z"), config); assertEquals(10, s.earned());
        s.refresh(Instant.parse("2026-09-28T07:00:00Z"), config); assertEquals(0, s.earned()); assertEquals(0, s.purchased(2000004));
        assertEquals(5, s.balance()); s.credit(3);
        s.refresh(Instant.parse("2026-09-27T00:00:00Z"), config); assertEquals(3, s.earned());
        s.refresh(Instant.parse("2026-09-28T07:00:00Z"), config); assertEquals(3, s.earned());
    }
    @Test void daylightSavingChangeStillResetsOnMondayMidnight() {
        var s = state(); s.refresh(Instant.parse("2026-10-31T12:00:00Z"), config); s.credit(10);
        s.refresh(Instant.parse("2026-11-02T07:59:59Z"), config); assertEquals(10, s.earned());
        s.refresh(Instant.parse("2026-11-02T08:00:00Z"), config); assertEquals(0, s.earned());
    }
    @Test void badSavedDataMustNotResetCurrency() {
        for (String bad : new String[]{"", "2|2026-09-21|1|0|0|0|", "1|2026-09-21|-1|0|0|0|", "1|invalid|1|0|0|0|", "1|2026-09-21|1|0|0|8|"})
            assertThrows(RuntimeException.class, () -> PcCafeState.decode(bad));
    }
    @Test void shippedSettingsAndRewardsAreValid() {
        assertEquals(15, PcCafe.roads().size()); assertEquals(25, PcCafe.rewards().size()); assertTrue(PcCafe.enabled());
    }
}

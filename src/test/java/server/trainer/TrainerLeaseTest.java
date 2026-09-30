package server.trainer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrainerLeaseTest {
    @Test void defaultOffAndLeaseExpires() {
        var s = new TrainerLease(1000); assertFalse(s.vac()); assertFalse(s.itemVac()); assertFalse(s.fma()); assertFalse(s.rapid());
        assertTrue(s.alive(5999)); assertFalse(s.alive(6000));
    }
    @Test void rapidRequiresFreshAcceptedAttackAndNeverCatchesUp() {
        var s = new TrainerLease(1000); s.configure(true, true, true, true, 150, 1000);
        assertFalse(s.takeRapid(1100)); s.acceptedAttack(1100);
        assertFalse(s.takeRapid(1249)); assertTrue(s.takeRapid(1250)); assertFalse(s.takeRapid(1250));
        assertTrue(s.takeRapid(1800)); assertFalse(s.takeRapid(1801)); assertFalse(s.takeRapid(1850));
    }
    @Test void offStopsEveryEffectAndBadCadenceCannotApply() {
        var s = new TrainerLease(1000); s.configure(true, true, true, true, 300, 1000); s.acceptedAttack(1000); s.off();
        assertFalse(s.takeRapid(1500)); assertFalse(s.takeVac(1500)); assertFalse(s.takeItemVac(1500)); assertFalse(s.fma());
        assertThrows(IllegalArgumentException.class, () -> s.configure(true, true, true, true, 0, 1500)); assertFalse(s.vac());
    }
    @Test void boundedVacAndRequestBudget() {
        var s = new TrainerLease(1000); s.configure(true, true, false, false, 300, 1000);
        assertTrue(s.takeVac(1000)); assertFalse(s.takeVac(1499)); assertTrue(s.takeVac(1500));
        assertTrue(s.takeItemVac(1000)); assertFalse(s.takeItemVac(1499)); assertTrue(s.takeItemVac(1500));
        for(int i=0;i<8;i++) assertTrue(s.allowRequest(2000)); assertFalse(s.allowRequest(2000)); assertTrue(s.allowRequest(3000));
    }
    @Test void expiredLeaseCannotPulse() {
        var s = new TrainerLease(1000); s.configure(true, true, true, true, 150, 1000); s.acceptedAttack(5900);
        assertFalse(s.takeRapid(6050)); assertFalse(s.takeVac(6050)); assertFalse(s.takeItemVac(6050));
    }
    @Test void panicRevokesInflightWriter() {
        var s = new TrainerLease(1000); s.configure(true,true,true,true,150,1000); s.revoke();
        assertFalse(s.alive(1001)); assertFalse(s.takeRapid(1200)); assertFalse(s.takeVac(1200)); assertFalse(s.takeItemVac(1200));
    }
    @Test void formRejectsDuplicatesOversizedAndMalformed() {
        assertEquals("a b", TrainerBridge.decode("code=a+b").get("code"));
        assertThrows(IllegalArgumentException.class, () -> TrainerBridge.decode("vac=0&vac=1"));
        assertThrows(IllegalArgumentException.class, () -> TrainerBridge.decode("x=%zz"));
        assertThrows(IllegalArgumentException.class, () -> TrainerBridge.decode("x="+"a".repeat(257)));
    }
    @Test void lootModesToggleWithoutRearmingCombat() {
        var s = new TrainerLease(1000);
        s.configure(true, true, false, true, 400, 25, true, true, 300, 1000);
        assertTrue(s.itemVac()); assertFalse(s.mesoVac()); assertTrue(s.lootOnKey());
        assertEquals(400, s.lootRadius()); assertEquals(25, s.lootBatch());
        assertFalse(s.takeItemVac(1000));
        s.configure(false, false, true, false, 0, 1, false, false, 300, 1100);
        assertFalse(s.vac()); assertFalse(s.itemVac()); assertTrue(s.mesoVac());
        assertTrue(s.takeItemVac(1100)); assertFalse(s.takeRapid(1500));
    }
    @Test void invalidLootOptionsLeavePreviousTogglesIntact() {
        var s = new TrainerLease(1000);
        s.configure(true, true, true, false, 0, 8, false, false, 300, 1000);
        assertThrows(IllegalArgumentException.class, () -> s.configure(false, false, false, true, 0, 26, true, true, 300, 1100));
        assertTrue(s.vac()); assertTrue(s.itemVac()); assertTrue(s.mesoVac()); assertFalse(s.rapid());
    }
    @Test void vacGeometryKeepsMobsAheadOfEitherFacing() {
        var actor = new java.awt.Point(100, 50);
        for (int index = 0; index < 100; index++) {
            assertTrue(TrainerVacGeometry.target(actor, 1, index).x >= 184);
            assertTrue(TrainerVacGeometry.target(actor, -1, index).x <= 16);
        }
    }
    @Test void wallVacUsesDistinctMapEdges() {
        var actor = new java.awt.Point(500, 50);
        var area = new java.awt.Rectangle(0, -200, 2000, 500);
        assertEquals(84, TrainerVacGeometry.target(actor, 1, 0, area, "left wall").x);
        assertEquals(1916, TrainerVacGeometry.target(actor, -1, 0, area, "right wall").x);
        assertEquals(584, TrainerVacGeometry.target(actor, 1, 0, area, "front").x);
    }
    @Test void lootFiltersValidateBeforeAConfigureCommits() {
        var options = TrainerLootOptions.parse("4000000, 4000001", "4000001", "newest", 10, 1000);
        assertEquals("newest", options.order);
        assertTrue(options.includeIds.contains(4000000));
        assertTrue(options.excludeIds.contains(4000001));
        assertThrows(IllegalArgumentException.class, () -> TrainerLootOptions.parse("abc", "", "nearest", 0, 100));
        assertThrows(IllegalArgumentException.class, () -> TrainerLootOptions.parse("", "", "random", 0, 100));
        assertThrows(IllegalArgumentException.class, () -> TrainerLootOptions.parse("", "", "nearest", 101, 100));
    }
    @Test void survivalPowersTickAndTurnOffIndependently() {
        var s = new TrainerLease(1000);
        s.configure(false, false, false, false, 0, 8, false, false, true, true, false, 300, 1000);
        assertTrue(s.hpGod()); assertTrue(s.hpRegen()); assertFalse(s.mpRegen());
        assertTrue(s.takeRegen(1000)); assertFalse(s.takeRegen(1500)); assertTrue(s.takeRegen(2000));
        s.configure(false, false, false, false, 0, 8, false, false, false, false, true, 300, 2100);
        assertFalse(s.hpGod()); assertFalse(s.hpRegen()); assertTrue(s.mpRegen());
        s.off(); assertFalse(s.takeRegen(3000)); assertFalse(s.mpRegen());
    }
    @Test void fmaPowerCanChangeAndPanicResetsIt() {
        var s = new TrainerLease(1000);
        s.fmaPower(25, true);
        assertEquals(25, s.fmaDamage()); assertTrue(s.fmaOneHit());
        assertThrows(IllegalArgumentException.class, () -> s.fmaPower(101, false));
        assertEquals(25, s.fmaDamage()); assertTrue(s.fmaOneHit());
        s.off();
        assertEquals(1, s.fmaDamage()); assertFalse(s.fmaOneHit());
    }
}

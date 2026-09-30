package soloMapling.ArtificialPlayer.CompanionSystem;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BossObjectiveTest {
    BossObjective hunt(String key) { return new BossObjective(1,100,new CompanionTaskService.PartyKey(0,4),
            BossRegistry.get(key).gatherMap(),BossRegistry.get(key),BossObjective.Mode.HUNT_TEMPLATE_IN_AREA,1000); }
    BossObjective.Identity identity(long map, long encounter) { return new BossObjective.Identity(0,1,map,encounter); }
    @Test void sameTemplateInAnotherChannelMapOrObjectCannotComplete() {
        var h = hunt("mushmom"); var actual = identity(10,20); assertTrue(h.observe(actual,6130101));
        h.damage(actual,6130101,100,1);
        assertFalse(h.removed(identity(11,20),6130101,true)); assertFalse(h.removed(identity(10,21),6130101,true));
        assertFalse(h.removed(new BossObjective.Identity(0,2,10,20),6130101,true));
        assertNull(h.outcome()); assertTrue(h.removed(actual,6130101,true)); assertEquals(BossObjective.Outcome.KILLED_WITH_CONTRIBUTION,h.outcome());
    }
    @Test void anotherPartysKillIsAnHonestSingleOutcome() {
        var h = hunt("mano"); h.observe(identity(1,1),2220000); h.damage(identity(1,1),2220000,555,1000);
        assertTrue(h.removed(identity(1,1),2220000,true)); assertEquals(BossObjective.Outcome.RESOLVED_BY_OTHERS,h.outcome());
        assertFalse(h.finish(BossObjective.Outcome.PLAYER_CANCELLED,"late"));
    }
    @Test void administrativeRemovalNeverAwardsAKillAfterContribution() {
        var h = hunt("mano"); h.damage(identity(1,1),2220000,100,1000);
        assertTrue(h.removed(identity(1,1),2220000,false)); assertEquals(BossObjective.Outcome.TARGET_DESPAWNED,h.outcome());
    }
    @Test void multipartRequiresFinalInTheSameEncounter() {
        var h = hunt("papulatus"); var id = identity(1,5); h.damage(id,8500001,100,100);
        assertFalse(h.removed(id,8500001,true)); assertFalse(h.removed(identity(1,6),8500002,true));
        assertTrue(h.observe(id,8500002)); assertTrue(h.removed(id,8500002,true));
        assertEquals(BossObjective.Outcome.KILLED_WITH_CONTRIBUTION,h.outcome());
    }
    @Test void laterPhaseCanBeAdoptedOnlyWithCanonicalAllowedRootProvenance() {
        var h=hunt("papulatus");var id=identity(1,5);
        assertFalse(h.observe(id,8500002));assertFalse(h.observe(id,8500002,9300210));assertNull(h.identity());
        assertTrue(h.observe(id,8500002,8500000));
        h.partyDamage(id,8500002,100,100);
        assertTrue(h.removed(id,8500002,true));assertEquals(BossObjective.Outcome.KILLED_WITH_CONTRIBUTION,h.outcome());
        var ht=hunt("horntail");assertFalse(ht.observe(id,8810026,8810026));
        assertTrue(ht.observe(id,8810002,8810026));
    }
    @Test void botGenerationAndHumanAuthorityAreSpecific() {
        var h = hunt("mano"); assertTrue(h.attach(7,9)); assertFalse(h.attach(7,9));
        assertFalse(h.command(555,100000000,555)); assertEquals(104000400,h.gatherMap());
        assertTrue(h.command(100,100000000,101)); assertTrue(h.command(101,104000400,null));
        h.finish(BossObjective.Outcome.PLAYER_CANCELLED,"cancel"); assertFalse(h.attach(8,10)); assertFalse(h.command(100,105090900,null));
    }
    @Test void onlyAllowedRootsBindAnEncounter() {
        var h = hunt("jr-balrog"); assertFalse(h.observe(identity(1,1),8150000)); assertNull(h.identity());
        assertFalse(h.observe(new BossObjective.Identity(1,1,1,1),8130100));
        assertTrue(h.observe(identity(1,2),8130100));
    }
    @Test void balrogSealTransitionsAndDefeatedClawObjectsNeverEndTheFight() {
        var h=hunt("instance-balrog");var id=identity(1,7);
        assertFalse(h.observe(id,8830006)); assertNull(h.identity());
        assertTrue(h.observe(id,8830002)); h.damage(id,8830002,100,100);
        assertFalse(h.removed(id,8830006,false)); assertFalse(h.removed(id,8830004,false));
        assertFalse(h.removed(id,8830002,true)); assertFalse(h.removed(id,8830001,true));
        assertTrue(h.removed(id,8830000,true)); assertEquals(BossObjective.Outcome.KILLED_WITH_CONTRIBUTION,h.outcome());
    }
}

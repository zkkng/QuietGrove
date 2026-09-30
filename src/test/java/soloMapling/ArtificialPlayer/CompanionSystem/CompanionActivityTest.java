package soloMapling.ArtificialPlayer.CompanionSystem;

import client.BuffStat;
import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionActivityTest {
    private final MapleMap map = mock(MapleMap.class);
    private final Party party = mock(Party.class);
    private Character member() {
        Character c = mock(Character.class);
        when(c.isAlive()).thenReturn(true);
        when(c.getMap()).thenReturn(map);
        when(c.getParty()).thenReturn(party);
        when(c.getCurrentMaxHp()).thenReturn(1000);
        when(c.getPosition()).thenReturn(new java.awt.Point(0, 0));
        return c;
    }
    @Test void idleZeroDamageAndOverhealNeverQualify() {
        Character c = member(), ally = member();
        CompanionActivity.damage(c, 0);
        CompanionActivity.healing(c, ally, 1000);
        assertFalse(CompanionActivity.active(c, List.of(c, ally), 0));
        CompanionActivity.damage(ally, 100);
        CompanionActivity.healing(c, ally, 0);
        CompanionActivity.healing(c, ally, 49);
        assertFalse(CompanionActivity.active(c, List.of(c, ally), 0));
    }
    @Test void effectiveHealingAnEngagedAllyQualifiesButSelfHealingDoesNot() {
        Character c = member(), ally = member();
        CompanionActivity.damage(ally, 100);
        CompanionActivity.healing(c, c, 500);
        assertFalse(CompanionActivity.active(c, List.of(c, ally), 0));
        CompanionActivity.healing(c, ally, 50);
        assertTrue(CompanionActivity.active(c, List.of(c, ally), 0));
    }
    @Test void smallHealsAccumulateOnTheSameEngagedAllyAndMapChangeClearsThem() {
        Character c = member(), ally = member();
        CompanionActivity.damage(ally, 100);
        CompanionActivity.healing(c, ally, 25);
        assertFalse(CompanionActivity.active(c, List.of(c, ally), 0));
        CompanionActivity.healing(c, ally, 25);
        assertTrue(CompanionActivity.active(c, List.of(c, ally), 0));
        when(c.getMap()).thenReturn(mock(MapleMap.class));
        assertFalse(CompanionActivity.active(c, List.of(c, ally), 0));
    }
    @Test void distantBuffCasterNeverReceivesCredit() {
        Character caster = member(), fighter = member();
        when(caster.getPosition()).thenReturn(new java.awt.Point(701, 0));
        when(fighter.getBuffSource(BuffStat.WATK)).thenReturn(100);
        CompanionActivity.granted(caster, fighter, 100, BuffStat.WATK, Long.MAX_VALUE);
        CompanionActivity.damage(fighter, 100);
        assertFalse(CompanionActivity.active(caster, List.of(caster, fighter), 0));
    }
    @Test void parkedCompanionBuffCannotEarnCreditButSupportDutyCan() {
        Character caster = member(), fighter = member();
        when(caster.getId()).thenReturn(21001); when(party.getId()).thenReturn(7);
        when(fighter.getBuffSource(BuffStat.WATK)).thenReturn(100);
        CompanionTaskService tasks = new CompanionTaskService(System::currentTimeMillis,30);
        var r = tasks.reserve(1,21001,new CompanionTaskService.PartyKey(0,7),1,1,1,
                System.currentTimeMillis()+15000,new CompanionTaskService.PriorActivity("TRAINING_BOT",100,101)).orElseThrow();
        var t = tasks.commit(r,()->true).orElseThrow();
        try(var leases=mockStatic(CompanionTaskService.class)) {
            leases.when(CompanionTaskService::shared).thenReturn(tasks);
            CompanionActivity.granted(caster,fighter,100,BuffStat.WATK,Long.MAX_VALUE);
            CompanionActivity.damage(fighter,100);
            assertFalse(CompanionActivity.active(caster,List.of(caster,fighter),0));
            tasks.transition(21001,t.generation(),1,CompanionTaskService.State.SUPPORT);
            CompanionActivity.damage(fighter,100);
            assertTrue(CompanionActivity.active(caster,List.of(caster,fighter),0));
        }
    }
    @Test void shortKillDamageQualifiesImmediatelyAndDeadNeverQualifies() {
        Character c = member();
        assertTrue(CompanionActivity.active(c, List.of(c), 1));
        when(c.isAlive()).thenReturn(false);
        assertFalse(CompanionActivity.active(c, List.of(c), 100));
    }
    @Test void mapAndPartyIdentityInvalidateOldEvidence() {
        Character c = member();
        CompanionActivity.damage(c, 100);
        assertTrue(CompanionActivity.active(c, List.of(c), 0));
        when(c.getMap()).thenReturn(mock(MapleMap.class));
        assertFalse(CompanionActivity.active(c, List.of(c), 0));
        CompanionActivity.damage(c, 100);
        when(c.getParty()).thenReturn(mock(Party.class));
        assertFalse(CompanionActivity.active(c, List.of(c), 0));
    }
    @Test void usefulBuffCreditRequiresActualBenefitAndCurrentSource() {
        Character caster = member(), fighter = member();
        CompanionActivity.granted(caster, fighter, 100, BuffStat.WATK, Long.MAX_VALUE);
        assertFalse(CompanionActivity.active(caster, List.of(caster, fighter), 0));
        when(fighter.getBuffSource(BuffStat.WATK)).thenReturn(200);
        CompanionActivity.damage(fighter, 100);
        assertFalse(CompanionActivity.active(caster, List.of(caster, fighter), 0));
        when(fighter.getBuffSource(BuffStat.WATK)).thenReturn(100);
        CompanionActivity.damage(fighter, 100);
        assertTrue(CompanionActivity.active(caster, List.of(caster, fighter), 0));
    }
    @Test void holySymbolOnlyCreditsAfterAwardedKillNotCurrentDamage() {
        Character priest = member(), fighter = member();
        CompanionActivity.granted(priest, fighter, 2311003, BuffStat.HOLY_SYMBOL, Long.MAX_VALUE);
        when(fighter.getBuffSource(BuffStat.HOLY_SYMBOL)).thenReturn(2311003);
        CompanionActivity.damage(fighter, 100);
        assertFalse(CompanionActivity.active(priest, List.of(priest, fighter), 0));
        CompanionActivity.awardedKill(List.of(fighter), false);
        assertFalse(CompanionActivity.active(priest, List.of(priest, fighter), 0));
        CompanionActivity.awardedKill(List.of(fighter), true);
        assertTrue(CompanionActivity.active(priest, List.of(priest, fighter), 0));
        CompanionActivity.clear(priest);
        CompanionActivity.damage(fighter, 100);
        CompanionActivity.awardedKill(List.of(fighter), true);
        assertFalse(CompanionActivity.active(priest, List.of(priest, fighter), 0));
    }
}

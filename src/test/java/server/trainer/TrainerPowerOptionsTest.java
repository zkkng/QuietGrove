package server.trainer;

import client.Disease;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrainerPowerOptionsTest {
    private static Map<String,String> fields() { return new HashMap<>(Map.of("noMpCost","0","noAmmo","0","zeroCooldown","0","cooldownSkills","","immunity","","damageMultiplier","1","oneHit","0","accuracy","0","roll","normal")); }
    @Test void malformedOrUnknownSettingRejectsWholeProfile() {
        var fields=fields(); fields.put("noAmmo","yes"); assertThrows(IllegalArgumentException.class,()->TrainerPowerOptions.parse(fields));
        fields.put("noAmmo","1"); fields.put("extra","1"); assertThrows(IllegalArgumentException.class,()->TrainerPowerOptions.parse(fields));
    }
    @Test void cooldownRequiresExplicitSkillsAndNeverAcceptsBattleshipState() {
        var fields=fields(); fields.put("zeroCooldown","1"); assertThrows(IllegalArgumentException.class,()->TrainerPowerOptions.parse(fields));
        fields.put("cooldownSkills","5221999"); assertThrows(IllegalArgumentException.class,()->TrainerPowerOptions.parse(fields));
        fields.put("cooldownSkills","2121007,2221007"); var options=TrainerPowerOptions.parse(fields);
        assertTrue(options.cooldown(2121007)); assertFalse(options.cooldown(2321008));
    }
    @Test void selectedImmunityCannotTouchUnrelatedBuffsOrUnknownDiseases() {
        var fields=fields(); fields.put("immunity","poison,seal"); var options=TrainerPowerOptions.parse(fields);
        assertEquals(Set.of(Disease.POISON,Disease.SEAL),options.immunity());
        fields.put("immunity","FISHABLE"); assertThrows(IllegalArgumentException.class,()->TrainerPowerOptions.parse(fields));
        assertThrows(UnsupportedOperationException.class,()->options.immunity().add(Disease.STUN));
    }
    @Test void multiplierUsesWideArithmeticAndMissesStayMissesUnlessExplicitlyAllowed() {
        var fields=fields(); fields.put("damageMultiplier","100"); var options=TrainerPowerOptions.parse(fields);
        assertEquals(199999,options.damage(Integer.MAX_VALUE,Long.MAX_VALUE,Integer.MAX_VALUE,0));
        assertEquals(0,options.damage(0,1000,5000,0));
        fields.put("accuracy","1"); assertTrue(TrainerPowerOptions.parse(fields).damage(0,1000,5000,0)>0);
    }
    @Test void oneHitAssignsHpOnceAcrossMultipleLinesAndZeroMultiplierIsRealZero() {
        var fields=fields(); fields.put("oneHit","1"); var options=TrainerPowerOptions.parse(fields);
        assertEquals(1234,options.damage(100,1000,1234,0)); assertEquals(0,options.damage(100,1000,1234,1));
        fields.put("oneHit","0"); fields.put("damageMultiplier","0"); assertEquals(0,TrainerPowerOptions.parse(fields).damage(100,1000,1234,0));
    }
    @Test void normalNonTrainerActorsHaveNoResourceOrCooldownException() {
        var actor=org.mockito.Mockito.mock(client.Character.class); var service=TrainerService.getInstance();
        assertFalse(service.noMpCost(actor)); assertFalse(service.noAmmo(actor));
        assertFalse(service.immune(actor,Disease.POISON)); assertFalse(service.cooldownBypass(actor,2121007));
        assertEquals(180,service.cooldownDisplay(actor,2121007,180));
    }
}

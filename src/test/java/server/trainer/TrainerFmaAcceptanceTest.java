package server.trainer;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.status.MonsterStatus;
import constants.skills.Bishop;
import net.server.channel.handlers.AbstractDealDamageHandler;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.StatEffect;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import tools.PacketCreator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainerFmaAcceptanceTest {
    private static AttackInfo attack() {
        var a=new AttackInfo(); a.targets=new LinkedHashMap<>(); a.trainerMaxDamage=100; a.numDamage=1; return a;
    }
    @Test void realAcceptanceSeamRejectsOwnershipAndFailedCostAndAcceptsEmptyRealSwing() throws Exception {
        var actor=mock(Character.class); var map=mock(MapleMap.class); var service=mock(TrainerService.class);
        when(actor.getMap()).thenReturn(map); when(actor.getJob()).thenReturn(Job.BEGINNER); when(actor.isAlive()).thenReturn(true);
        var handler=mock(AbstractDealDamageHandler.class,CALLS_REAL_METHODS);
        var apply=AbstractDealDamageHandler.class.getDeclaredMethod("applyAttack",AttackInfo.class,Character.class,int.class); apply.setAccessible(true);
        var a=attack();
        try(var trainer=mockStatic(TrainerService.class); var skills=mockStatic(SkillFactory.class)) {
            trainer.when(TrainerService::getInstance).thenReturn(service);
            when(map.isOwnershipRestricted(actor)).thenReturn(true); apply.invoke(handler,a,actor,1);
            assertFalse(a.trainerAccepted); verifyNoInteractions(service);
            when(map.isOwnershipRestricted(actor)).thenReturn(false); apply.invoke(handler,a,actor,1);
            verify(service).onAcceptedAttack(actor,a,1); assertTrue(a.trainerAccepted); clearInvocations(service);
            a.skill=Bishop.BIG_BANG; var skill=mock(Skill.class); var effect=mock(StatEffect.class);
            skills.when(()->SkillFactory.getSkill(a.skill)).thenReturn(skill); when(actor.getSkillLevel(skill)).thenReturn((byte)1);
            when(skill.getEffect(1)).thenReturn(effect); when(effect.getMobCount()).thenReturn(1); when(effect.applyTo(actor)).thenReturn(false);
            apply.invoke(handler,a,actor,1); assertFalse(a.trainerAccepted); verifyNoInteractions(service);
            when(effect.applyTo(actor)).thenReturn(true); apply.invoke(handler,a,actor,1); verify(service).onAcceptedAttack(actor,a,1);
        }
    }
    @Test void rangedReceiptExpandsOnceUsesProjectileAndSkipsProtectedTargets() throws Exception {
        var f=new TrainerProfileSessionTest(); f.setup();
        try(var packets=mockStatic(PacketCreator.class); var reactions=mockStatic(TrainerBotReactions.class)) {
            var profile=TrainerProfileSessionTest.profile(); profile.put("core",profile.get("core").replace("fma=0","fma=1"));
            f.service.request("test","profile",profile);
            var ordinary=mob(f.map,1); var boss=mob(f.map,2); when(boss.isBoss()).thenReturn(true);
            var reflect=mob(f.map,3); when(reflect.isBuffed(MonsterStatus.MAGIC_REFLECT)).thenReturn(true);
            var eventMob=mob(mock(MapleMap.class),4); when(eventMob.getMap().getEventInstance()).thenReturn(mock(EventInstanceManager.class));
            when(f.map.getMonsters()).thenReturn(List.of(ordinary,boss,reflect,eventMob)); when(f.map.damageMonster(eq(f.actor),eq(ordinary),anyInt())).thenReturn(true);
            var a=attack(); a.ranged=true; a.trainerProjectile=2070006; a.trainerAccepted=true;
            f.service.onAcceptedAttack(f.actor,a,2); f.service.onAcceptedAttack(f.actor,a,2);
            verify(f.map,times(1)).damageMonster(eq(f.actor),eq(ordinary),anyInt());
            verify(f.map,never()).damageMonster(eq(f.actor),eq(boss),anyInt()); verify(f.map,never()).damageMonster(eq(f.actor),eq(reflect),anyInt());
            verify(f.map,never()).damageMonster(eq(f.actor),eq(eventMob),anyInt());
            packets.verify(()->PacketCreator.rangedAttack(eq(f.actor),eq(0),eq(0),eq(0),eq(18),eq(2070006),anyMap(),eq(4),eq(0),eq(0)));
            assertEquals("1",f.service.request("test","status",Map.of()).get("fmaCasts"));
        } finally { f.restore(); }
    }
    private static Monster mob(MapleMap map,int id) {
        var m=mock(Monster.class); when(m.getObjectId()).thenReturn(id); when(m.getMap()).thenReturn(map); when(m.isAlive()).thenReturn(true);
        when(m.getStats()).thenReturn(mock(MonsterStats.class)); when(m.getHp()).thenReturn(1000); when(map.getMonsterByOid(id)).thenReturn(m); return m;
    }
    @Test void magicPacketRetainsChargeAndRejectsMalformedCounts() {
        var actor=mock(Character.class); var a=attack(); a.magic=true; a.skill=Bishop.BIG_BANG; a.charge=987;
        Map<Integer,AttackTarget> targets=Map.of(1,new AttackTarget((short)0,List.of(50,60)));
        try(var packets=mockStatic(PacketCreator.class)) {
            TrainerFmaPolicy.packet(actor,a,targets);
            packets.verify(()->PacketCreator.magicAttack(actor,Bishop.BIG_BANG,0,0,18,targets,987,4,0,0));
            assertThrows(IllegalArgumentException.class,()->TrainerFmaPolicy.packet(actor,a,Map.of(1,new AttackTarget((short)0,List.of()))));
        }
        assertFalse(TrainerFmaPolicy.supported(constants.skills.Cleric.HEAL));
        assertFalse(TrainerFmaPolicy.supported(constants.skills.ChiefBandit.MESO_EXPLOSION));
    }
}

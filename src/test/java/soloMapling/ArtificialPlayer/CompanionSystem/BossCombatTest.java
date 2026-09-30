package soloMapling.ArtificialPlayer.CompanionSystem;

import client.*;
import client.Character;
import client.inventory.*;
import client.status.*;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.StatEffect;
import server.life.*;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossCombatTest {
    @org.junit.jupiter.api.BeforeAll static void initializeItemData() throws Exception {
        var connection=mock(java.sql.Connection.class,RETURNS_DEEP_STUBS);
        try(var database=mockStatic(tools.DatabaseConnection.class)) {
            database.when(tools.DatabaseConnection::getConnection).thenReturn(connection);
            ItemInformationProvider.getInstance();
        }
    }
    @Test void reflectionActivatedAfterAttackRollPreventsBossDamageAndUsesCurrentCounterSkill() {
        var bot=mock(Character.class);var monster=mock(Monster.class);var map=mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);when(monster.getMap()).thenReturn(map);when(bot.isAlive()).thenReturn(true);
        var counter=mock(MobSkill.class);when(counter.getType()).thenReturn(MobSkillType.PHYSICAL_AND_MAGIC_COUNTER);
        when(counter.getX()).thenReturn(450);when(counter.getY()).thenReturn(650);
        var status=new MonsterStatusEffect(Map.of(MonsterStatus.WEAPON_REFLECT,10,MonsterStatus.MAGIC_REFLECT,10),null,counter,true);
        when(monster.getStati(MonsterStatus.WEAPON_REFLECT)).thenReturn(status);
        when(monster.getStati(MonsterStatus.MAGIC_REFLECT)).thenReturn(status);
        var packet=mock(net.packet.Packet.class);
        doAnswer(call->{
            when(monster.isBuffed(MonsterStatus.WEAPON_REFLECT)).thenReturn(true);
            when(monster.isBuffed(MonsterStatus.MAGIC_REFLECT)).thenReturn(true);
            return null;
        }).when(map).broadcastMessage(eq(bot),eq(packet),eq(false));
        try(var runtime=mockStatic(CompanionRuntime.class);var packets=mockStatic(tools.PacketCreator.class);
            var movement=mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            runtime.when(()->CompanionRuntime.active(bot)).thenReturn(true);
            runtime.when(()->CompanionRuntime.combatAllowed(bot,71L)).thenReturn(true);
            packets.when(()->tools.PacketCreator.closeRangeAttack(eq(bot),anyInt(),anyInt(),anyInt(),anyInt(),anyMap(),anyInt(),anyInt(),anyInt())).thenReturn(packet);
            packets.when(()->tools.PacketCreator.magicAttack(eq(bot),anyInt(),anyInt(),anyInt(),anyInt(),anyMap(),anyInt(),anyInt(),anyInt(),anyInt())).thenReturn(packet);
            assertFalse(BotAttackEffects.withAuthority(bot,71L,()->BotAttackEffects.meleeStrike(bot,Map.of(monster,List.of(5000)),0,0,0,0,4,(short)300)));
            verify(bot,times(1)).addHP(-450);
            assertFalse(BotAttackEffects.withAuthority(bot,71L,()->BotAttackEffects.magicStrike(bot,Map.of(monster,List.of(5000)),2001004,1,0,0,4,(short)300)));
            verify(bot,times(1)).addHP(-650);
            verify(map,never()).damageMonster(eq(bot),eq(monster),anyInt(),anyShort());
            verify(bot,never()).addMP(anyInt());
        }
    }
    @Test void cancelledAuthorityAndWrongMapCannotReceiveReflectionCounter() {
        var bot=mock(Character.class);var target=mock(Monster.class);var map=mock(MapleMap.class);
        var counter=mock(MobSkill.class);when(counter.getX()).thenReturn(900);
        when(target.isBuffed(MonsterStatus.WEAPON_REFLECT)).thenReturn(true);
        when(target.getStati(MonsterStatus.WEAPON_REFLECT)).thenReturn(new MonsterStatusEffect(Map.of(MonsterStatus.WEAPON_REFLECT,10),null,counter,true));
        when(bot.isAlive()).thenReturn(true);when(bot.getMap()).thenReturn(map);when(target.getMap()).thenReturn(map);
        try(var runtime=mockStatic(CompanionRuntime.class)) {
            assertTrue(BotAttackEffects.withAuthority(bot,81L,()->BotAttackEffects.resolveProtection(bot,target,false)));
            runtime.when(()->CompanionRuntime.combatAllowed(bot,81L)).thenReturn(true);
            when(target.getMap()).thenReturn(mock(MapleMap.class));
            assertTrue(BotAttackEffects.withAuthority(bot,81L,()->BotAttackEffects.resolveProtection(bot,target,false)));
            verify(bot,never()).addHP(anyInt());verify(map,never()).broadcastMessage(any(Character.class),any(net.packet.Packet.class),anyBoolean());
        }
    }
    @Test void immunityAtEffectTimeProtectsOnlyItsAttackKindWithoutCounterDamage() {
        var bot=mock(Character.class);var target=mock(Monster.class);
        when(target.isBuffed(MonsterStatus.WEAPON_IMMUNITY)).thenReturn(true);
        assertTrue(BotAttackEffects.resolveProtection(bot,target,false));assertFalse(BotAttackEffects.resolveProtection(bot,target,true));
        verify(bot,never()).addHP(anyInt());
    }
    @Test void accuracyIncludesEquipmentLevelGapAndActualDarkness() {
        var bot=mock(Character.class); var monster=mock(Monster.class); var stats=new MonsterStats(); stats.setAvoidability(100);
        when(monster.getStats()).thenReturn(stats); when(monster.getLevel()).thenReturn(50);
        when(bot.getLevel()).thenReturn(50); when(bot.getJob()).thenReturn(Job.FIGHTER);
        when(bot.getTotalDex()).thenReturn(100); when(bot.getTotalLuk()).thenReturn(20);
        var inventory=mock(Inventory.class); var equip=new Equip(1302000,(short)-11); equip.setAcc((short)10);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(inventory); when(inventory.list()).thenReturn(List.of(equip));
        assertEquals(100/184.0,CompanionCombat.hitChance(bot,monster,false),.00001);
        when(bot.getLevel()).thenReturn(40); assertTrue(CompanionCombat.hitChance(bot,monster,false)<100/184.0);
        double clear=CompanionCombat.hitChance(bot,monster,false); when(bot.hasDisease(Disease.DARKNESS)).thenReturn(true);
        assertEquals(clear*.5,CompanionCombat.hitChance(bot,monster,false),.00001);
    }
    @Test void monsterDefenseBuffAndDebuffUseSeparatePercentAndFlatValues() {
        var monster=mock(Monster.class);
        when(monster.effectiveStat(anyInt(),any(),any())).thenCallRealMethod();
        assertEquals(200,monster.effectiveStat(200,MonsterStatus.WEAPON_DEFENSE_UP,MonsterStatus.WDEF));
        when(monster.getStati(MonsterStatus.WEAPON_DEFENSE_UP)).thenReturn(new MonsterStatusEffect(Map.of(MonsterStatus.WEAPON_DEFENSE_UP,150),null,null,true));
        when(monster.getStati(MonsterStatus.WDEF)).thenReturn(new MonsterStatusEffect(Map.of(MonsterStatus.WDEF,-25),null,null,false));
        assertEquals(275,monster.effectiveStat(200,MonsterStatus.WEAPON_DEFENSE_UP,MonsterStatus.WDEF));
    }
    @Test void weaponReflectionAndMagicImmunityOnlyProtectTheirOwnAttackKind() {
        var monster=mock(Monster.class); when(monster.isBuffed(MonsterStatus.WEAPON_REFLECT)).thenReturn(true);
        assertTrue(CompanionCombat.protectedFrom(monster,false)); assertFalse(CompanionCombat.protectedFrom(monster,true));
        when(monster.isBuffed(MonsterStatus.MAGIC_IMMUNITY)).thenReturn(true); assertTrue(CompanionCombat.protectedFrom(monster,true));
    }
    @Test void lethalIncomingDamageAndMagicGuardUseRealHpMpAndSharedInvulnerability() {
        var bot=mock(Character.class); when(bot.isAlive()).thenReturn(true); when(bot.getHp()).thenReturn(100);
        when(bot.getMp()).thenReturn(40); when(bot.getBuffedValue(BuffStat.MAGIC_GUARD)).thenReturn(80);
        try(var runtime=mockStatic(CompanionRuntime.class)) {
            runtime.when(()->CompanionRuntime.active(bot)).thenReturn(true);
            assertEquals(60,CompanionIncomingDamage.applyAttack(bot,100,false,false,0));
            verify(bot).addMP(-40); verify(bot).addHP(-60);
            assertEquals(-1,CompanionIncomingDamage.applyAttack(bot,100,false,false,0));
            CompanionIncomingDamage.clear(bot);
            assertEquals(99,CompanionIncomingDamage.applyAttack(bot,500,false,true,0));
            verify(bot).addMP(-39); verify(bot).addHP(-99);
            CompanionIncomingDamage.clear(bot);
            assertEquals(200,CompanionIncomingDamage.apply(bot,240,false)); // Magic Guard spends only the real 40 MP; HP loss can kill.
            verify(bot).addHP(-200); CompanionIncomingDamage.clear(bot);
        }
    }
    @Test void finitePotionsHonorStatusCooldownAndCancelledAuthority() {
        var bot=mock(Character.class); var map=mock(MapleMap.class); when(bot.getMap()).thenReturn(map);
        when(bot.isAlive()).thenReturn(true); when(bot.getHp()).thenReturn(100); when(bot.getCurrentMaxHp()).thenReturn(1000);
        var inventory=new Inventory(bot,InventoryType.USE,(byte)1); inventory.addItem(new Item(2000002,(short)0,(short)1));
        when(bot.getInventory(InventoryType.USE)).thenReturn(inventory);
        var items=mock(ItemInformationProvider.class); var potion=mock(StatEffect.class);
        when(items.getItemEffect(2000002)).thenReturn(potion); when(potion.getHp()).thenReturn((short)300);
        try(var providers=mockStatic(ItemInformationProvider.class);var effects=mockStatic(BotAttackEffects.class)) {
            providers.when(ItemInformationProvider::getInstance).thenReturn(items); var combat=new CompanionCombat();
            assertFalse(combat.potion(bot,1000)); assertEquals(1,inventory.countById(2000002));
            effects.when(()->BotAttackEffects.authorityValid(bot)).thenReturn(true);
            when(bot.hasDisease(Disease.SEDUCE)).thenReturn(true); assertFalse(combat.potion(bot,1000));
            when(bot.hasDisease(Disease.SEDUCE)).thenReturn(false); assertTrue(combat.potion(bot,1000));
            assertEquals(0,inventory.countById(2000002)); verify(bot,times(1)).addMPHP(300,0);
            assertFalse(combat.potion(bot,1500)); assertFalse(combat.potion(bot,2500));
        }
    }
    @Test void flyingMovementIsBoundedAndNeverUsesGroundFootholdSnaps() {
        var map=mock(MapleMap.class); var monster=mock(Monster.class); var target=mock(Character.class);
        var stats=new MonsterStats();stats.animationTimes.put("fly",500);
        when(monster.getStats()).thenReturn(stats); when(monster.getMap()).thenReturn(map);
        when(monster.getPosition()).thenReturn(new Point()); when(target.getPosition()).thenReturn(new Point(100,0));
        when(map.getMapArea()).thenReturn(new Rectangle(-10,-10,50,50));
        BossMonsterController.moveOnTerrain(monster,target,true); verify(monster).setPosition(new Point(20,0));
        clearInvocations(monster,map); when(monster.getPosition()).thenReturn(new Point(39,0));
        BossMonsterController.moveOnTerrain(monster,target,true); verify(monster,never()).setPosition(any());
        verify(map,never()).getFootholds();
    }
}

package soloMapling.ArtificialPlayer.CompanionSystem;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.skills.Cleric;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.ItemInformationProvider;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import tools.Pair;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanionCombatTest {
    @Test void learnedHolySymbolProactivelyAppliesToHumanAndBotAlliesWithOnePayment() {
        Character bot=mock(Character.class), human=mock(Character.class), companion=mock(Character.class);
        Skill skill=mock(Skill.class); StatEffect hs=mock(StatEffect.class);
        MapleMap map=mock(MapleMap.class); Inventory inventory=mock(Inventory.class);
        when(bot.getJob()).thenReturn(client.Job.PRIEST); when(bot.getMap()).thenReturn(map);
        when(human.isAlive()).thenReturn(true); when(companion.isAlive()).thenReturn(true);
        when(human.getMap()).thenReturn(map); when(companion.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point()); when(human.getPosition()).thenReturn(new Point(50,0));
        when(companion.getPosition()).thenReturn(new Point(100,0));
        when(bot.getHp()).thenReturn(100); when(bot.getMp()).thenReturn(100);
        when(bot.getInventory(any())).thenReturn(inventory); when(hs.getMpCon()).thenReturn((short)24);
        when(bot.applyHpMpChange(0,0,-24)).thenReturn(true);
        when(bot.getSkillLevel(skill)).thenReturn((byte)30); when(skill.getEffect(30)).thenReturn(hs);
        when(hs.isPartyBuff()).thenReturn(true); when(hs.getStatups()).thenReturn(List.of(new Pair<>(BuffStat.HOLY_SYMBOL,50)));
        when(hs.getAttackBox(any(),anyBoolean())).thenReturn(new Rectangle(-300,-200,600,400));
        when(hs.applyToTarget(bot,human)).thenReturn(true); when(hs.applyToTarget(bot,companion)).thenReturn(true);
        try(var skills=mockStatic(SkillFactory.class)) {
            skills.when(()->SkillFactory.getSkill(constants.skills.Priest.HOLY_SYMBOL)).thenReturn(skill);
            assertTrue(new CompanionCombat().buff(bot,List.of(human,companion),1000));
            verify(hs).applyToTarget(bot,human); verify(hs).applyToTarget(bot,companion);
            verify(bot,times(1)).applyHpMpChange(0,0,-24);
            when(bot.getSkillLevel(skill)).thenReturn((byte)0);
            assertFalse(new CompanionCombat().buff(bot,List.of(human,companion),1000));
            verify(bot,times(1)).applyHpMpChange(0,0,-24);
        }
    }
    @Test void laterItemOrMesoFailureNeverConsumesAmmo() {
        Character bot = mock(Character.class);
        Inventory inventory = mock(Inventory.class);
        StatEffect effect = mock(StatEffect.class);
        when(bot.getHp()).thenReturn(100); when(bot.getMp()).thenReturn(100);
        when(bot.getInventory(any())).thenReturn(inventory);
        when(inventory.getSlotLimit()).thenReturn((byte)10);
        when(inventory.getItem((short)1)).thenReturn(new Item(2060000, (short)1, (short)10));
        when(effect.getMoneyCon()).thenReturn(1);
        assertFalse(CompanionCombat.payAttack(bot, effect, client.inventory.WeaponType.BOW, 2));
        when(effect.getMoneyCon()).thenReturn(0);
        when(effect.getItemCon()).thenReturn(4006000); when(effect.getItemConNo()).thenReturn(1);
        assertFalse(CompanionCombat.payAttack(bot, effect, client.inventory.WeaponType.BOW, 2));
        verify(inventory, never()).removeItem(anyShort(), anyShort(), anyBoolean());
        verify(bot, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
    }
    @Test void missingAmmoNeverPaysSkillResources() {
        Character bot = mock(Character.class);
        Inventory inventory = mock(Inventory.class);
        when(bot.getInventory(any())).thenReturn(inventory);
        assertFalse(CompanionCombat.payAttack(bot, mock(StatEffect.class), client.inventory.WeaponType.BOW, 2));
        verify(bot, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
    }
    @Test void failedResourcePaymentDoesNotChargeOrRemoveItems() {
        Character bot = mock(Character.class);
        StatEffect effect = mock(StatEffect.class);
        when(bot.getHp()).thenReturn(100);
        when(bot.getMp()).thenReturn(10);
        when(effect.getMpCon()).thenReturn((short)24);
        assertFalse(CompanionCombat.pay(bot, effect));
        verify(bot, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
        verify(bot, never()).getInventory(any());
    }
    @Test void skillPaymentUsesDirectCharacterAndFiniteInventory() {
        Character bot = mock(Character.class);
        StatEffect effect = mock(StatEffect.class);
        Inventory inventory = mock(Inventory.class);
        Item rock = new Item(4006000, (short)1, (short)2);
        when(bot.getHp()).thenReturn(100); when(bot.getMp()).thenReturn(100);
        when(bot.getMeso()).thenReturn(50);
        when(bot.getInventory(any())).thenReturn(inventory);
        when(effect.getMpCon()).thenReturn((short)24);
        when(effect.getItemCon()).thenReturn(4006000); when(effect.getItemConNo()).thenReturn(1);
        when(effect.getMoneyCon()).thenReturn(10);
        when(inventory.countById(4006000)).thenReturn(2);
        when(inventory.getSlotLimit()).thenReturn((byte)10);
        when(inventory.getItem((short)1)).thenReturn(rock);
        when(bot.applyHpMpChange(0, 0, -24)).thenReturn(true);
        assertTrue(CompanionCombat.pay(bot, effect));
        verify(inventory).removeItem((short)1, (short)1, false);
        verify(bot).gainMeso(-10, false);
        verify(bot, never()).getClient();
    }
    @Test void noInventoryStockCannotBeInventedBySkillPayment() {
        Character bot = mock(Character.class);
        StatEffect effect = mock(StatEffect.class);
        Inventory inventory = mock(Inventory.class);
        when(bot.getHp()).thenReturn(100); when(bot.getMp()).thenReturn(100);
        when(bot.getInventory(any())).thenReturn(inventory);
        when(effect.getItemCon()).thenReturn(4006000); when(effect.getItemConNo()).thenReturn(1);
        assertFalse(CompanionCombat.pay(bot, effect));
        verify(bot, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
    }
    @Test void strongerBuffBlocksWholeWeakerCastAndRefreshWaitsUntilLastTenPercent() {
        Character target = mock(Character.class);
        StatEffect proposed = mock(StatEffect.class), existing = mock(StatEffect.class);
        when(proposed.getStatups()).thenReturn(List.of(new Pair<>(BuffStat.HOLY_SYMBOL, 30)));
        when(target.getBuffedValue(BuffStat.HOLY_SYMBOL)).thenReturn(50);
        assertFalse(CompanionCombat.needsBuff(target, proposed, 1000));
        when(target.getBuffedValue(BuffStat.HOLY_SYMBOL)).thenReturn(30);
        when(target.getBuffEffect(BuffStat.HOLY_SYMBOL)).thenReturn(existing);
        when(target.getBuffedStarttime(BuffStat.HOLY_SYMBOL)).thenReturn(0L);
        when(existing.getDuration()).thenReturn(100000);
        assertFalse(CompanionCombat.needsBuff(target, proposed, 89999));
        assertTrue(CompanionCombat.needsBuff(target, proposed, 90000));
    }
    @Test void oneHealCastChargesOnceAndFiltersLivingMonstersBeforeCap() {
        Character bot = mock(Character.class), ally = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Inventory inventory = mock(Inventory.class);
        Skill skill = mock(Skill.class);
        StatEffect heal = mock(StatEffect.class);
        Monster living = mock(Monster.class), undead = mock(Monster.class);
        MonsterStats livingStats = new MonsterStats(), undeadStats = new MonsterStats();
        undeadStats.undead = true;
        when(living.getStats()).thenReturn(livingStats); when(undead.getStats()).thenReturn(undeadStats);
        when(living.isAlive()).thenReturn(true); when(undead.isAlive()).thenReturn(true);
        when(living.getPosition()).thenReturn(new Point(10, 0)); when(undead.getPosition()).thenReturn(new Point(20, 0));
        when(bot.getPosition()).thenReturn(new Point(0,0)); when(bot.getMap()).thenReturn(map);
        when(ally.isAlive()).thenReturn(true); when(ally.getMap()).thenReturn(map);
        when(bot.getSkillLevel(skill)).thenReturn((byte)30); when(skill.getEffect(30)).thenReturn(heal);
        when(bot.getSkillLevel(Cleric.HEAL)).thenReturn(30);
        when(heal.getAttackBox(any(), anyBoolean())).thenReturn(new Rectangle(-300,-200,600,400));
        when(heal.getMobCount()).thenReturn(1); when(heal.getHp()).thenReturn((short)300);
        when(heal.getMpCon()).thenReturn((short)24);
        when(bot.getHp()).thenReturn(500); when(bot.getMp()).thenReturn(100); when(bot.getCurrentMaxHp()).thenReturn(500);
        when(bot.getTotalInt()).thenReturn(100); when(bot.getTotalLuk()).thenReturn(20); when(bot.getTotalMagic()).thenReturn(200);
        when(bot.getInventory(any())).thenReturn(inventory);
        when(bot.applyHpMpChange(0,0,-24)).thenReturn(true);
        when(ally.getPosition()).thenReturn(new Point(30,0)); when(ally.getHp()).thenReturn(500); when(ally.getCurrentMaxHp()).thenReturn(1000);
        when(map.getMapObjectsInRange(any(), anyDouble(), anyList())).thenReturn(List.of(living, undead));
        try (var factory = mockStatic(SkillFactory.class); var effects = mockStatic(BotAttackEffects.class);
             var attacks = mockStatic(BotAttack.class); var data = mockStatic(BotAttackData.class)) {
            effects.when(() -> BotAttackEffects.authorityValid(bot)).thenReturn(true);
            factory.when(() -> SkillFactory.getSkill(Cleric.HEAL)).thenReturn(skill);
            attacks.when(() -> BotAttack.resolveEquippedWeaponType(bot)).thenReturn(client.inventory.WeaponType.STAFF);
            assertTrue(new CompanionCombat().heal(bot, List.of(ally), 1000));
            verify(bot, times(1)).applyHpMpChange(0,0,-24);
            verify(ally).addHP(1500);
            effects.verify(() -> BotAttackEffects.magicStrike(eq(bot), argThat(hits -> hits.size() == 1
                    && hits.containsKey(undead) && !hits.containsKey(living)), eq(Cleric.HEAL), eq(30), anyInt(), anyInt(), anyInt(), anyShort()));
        }
    }
}

package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.Disease;
import client.inventory.*;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.StatEffect;
import server.life.*;
import server.maps.*;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import java.awt.*;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossPositionsTest {
    @Test void horntailHeadRequiresAnActualPlatformNearItsOffsetBody() {
        var map=mock(MapleMap.class);var bot=mock(Character.class);var leader=mock(Character.class);var head=mock(Monster.class);
        when(bot.getMap()).thenReturn(map);when(bot.getPosition()).thenReturn(new Point(0,-1));
        when(leader.getPosition()).thenReturn(new Point(0,-1));when(head.getPosition()).thenReturn(new Point(0,0));
        when(head.getId()).thenReturn(8810002);when(head.isBoss()).thenReturn(true);
        when(map.getMapArea()).thenReturn(new Rectangle(-1000,-1000,2000,2000));
        var tree=new FootholdTree(new Point(-1000,-1000),new Point(1000,1000));when(map.getFootholds()).thenReturn(tree);
        tree.insert(new Foothold(new Point(-500,0),new Point(500,0),1));
        assertFalse(BossGeometry.reaches(new Rectangle(-90,-61,180,120),head,bot.getPosition(),60));
        assertTrue(BossPositions.engagement(bot,leader,head,BotAttackProfile.basicSwing(),null).isEmpty());
        when(leader.getPosition()).thenReturn(new Point(0,-401));
        tree.insert(new Foothold(new Point(-500,-400),new Point(500,-400),2));
        Point goal=BossPositions.engagement(bot,leader,head,BotAttackProfile.basicSwing(),null).orElseThrow();
        assertEquals(-401,goal.y);
        assertTrue(BossGeometry.reaches(new Rectangle(goal.x-90,goal.y-60,180,120),head,goal,60));
    }
    @Test void unknownBossBodyCannotBeCreditedAsAnAnchorPointHit() {
        var boss=mock(Monster.class);when(boss.getId()).thenReturn(Integer.MAX_VALUE);
        when(boss.isBoss()).thenReturn(true);when(boss.getPosition()).thenReturn(new Point(0,0));
        assertFalse(BossGeometry.reaches(new Rectangle(-100,-100,200,200),boss,new Point(0,0),100));
    }
    @Test void unreachableRoofIsHeldAndAttackableFloorPreservesRangedSpacing() {
        var map=mock(MapleMap.class);var bot=mock(Character.class);var leader=mock(Character.class);var target=mock(Monster.class);
        when(bot.getMap()).thenReturn(map);when(bot.getPosition()).thenReturn(new Point(0,-1));
        when(leader.getPosition()).thenReturn(new Point(0,-1));when(target.getPosition()).thenReturn(new Point(200,-1));
        when(map.getMapArea()).thenReturn(new Rectangle(-1000,-1000,2000,2000));
        var tree=new FootholdTree(new Point(-1000,-1000),new Point(1000,1000));when(map.getFootholds()).thenReturn(tree);
        tree.insert(new Foothold(new Point(-500,-300),new Point(500,-300),1));
        var profile=BotAttackProfile.ranged(0,1);
        assertTrue(BossPositions.engagement(bot,leader,target,profile,null).isEmpty());
        tree.insert(new Foothold(new Point(-500,0),new Point(500,0),2));
        Point goal=BossPositions.engagement(bot,leader,target,profile,null).orElseThrow();
        assertEquals(-1,goal.y);assertTrue(Math.abs(goal.x-200)>=200);
        when(bot.getSkillLevel(constants.skills.Cleric.HEAL)).thenReturn(1);
        assertEquals(350,BossPositions.leashX(bot));assertEquals(160,BossPositions.leashY(bot));
    }
    @Test void disablingCureAdmissionConsumesNothingAndUsesRealDeclaredItemEffects() {
        var bot=mock(Character.class);var use=new Inventory(bot,InventoryType.USE,(byte)4);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        var items=mock(ItemInformationProvider.class);var cure=mock(StatEffect.class);
        when(items.getItemEffect(2050004)).thenReturn(cure);when(cure.getCureDebuffs()).thenReturn(List.of(Disease.SEAL,Disease.DARKNESS));
        try(var providers=mockStatic(ItemInformationProvider.class)) {
            providers.when(ItemInformationProvider::getInstance).thenReturn(items);
            assertFalse(BossThreats.cleanseFailure(bot,Set.of(Disease.SEAL)).isEmpty());
            use.addItem(new Item(2050004,(short)0,(short)2));
            assertFalse(BossThreats.cleanseFailure(bot,Set.of(Disease.SEAL)).isEmpty());
            use.getItem((short)1).setQuantity((short)3);
            assertEquals("",BossThreats.cleanseFailure(bot,Set.of(Disease.SEAL,Disease.DARKNESS)));
            assertEquals(3,use.countById(2050004));
        }
        assertTrue(BossRegistry.get("horntail").threats().skills().contains(MobSkillType.SEDUCE));
        assertFalse(BossRegistry.get("mano").threats().disablingCleanses().contains(Disease.SEAL));
    }
}

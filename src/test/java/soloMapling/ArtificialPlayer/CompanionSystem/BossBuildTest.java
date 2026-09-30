package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.Job;
import client.Skill;
import client.inventory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import server.ItemInformationProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossBuildTest {
    @Test void actualCharacterGrowthPreservesHealthAndManaFractionsWithoutOldMaximumClamping() {
        boolean ratio=config.YamlConfig.config.server.USE_FIXED_RATIO_HPMP_UPDATE;
        try(var skills=mockStatic(client.SkillFactory.class)) {
            config.YamlConfig.config.server.USE_FIXED_RATIO_HPMP_UPDATE=false;
            var bot=Character.getDefault(mock(client.Client.class));bot.setID(24009);bot.setName("BuildGrowthBot");
            bot.setLevel(20);bot.setJob(Job.WARRIOR);
            bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(new Equip(1302000,(short)-11));
            bot.updateHp(25);bot.updateMp(2);
            double hp=bot.getHp()/(double)bot.getCurrentMaxHp(),mp=bot.getMp()/(double)bot.getCurrentMaxMp();
            CompanionBuild.initializeAmbient(bot);
            assertTrue(bot.getCurrentMaxHp()>=950);assertTrue(bot.getCurrentMaxMp()>=270);
            assertEquals((int)(bot.getCurrentMaxHp()*hp),bot.getHp());
            assertEquals((int)(bot.getCurrentMaxMp()*mp),bot.getMp());
            int health=bot.getHp(),mana=bot.getMp();
            CompanionBuild.initializeAmbient(bot);
            assertEquals(health,bot.getHp());assertEquals(mana,bot.getMp());
            assertEquals(50,bot.getInventory(InventoryType.USE).countById(2000002));
        }finally {config.YamlConfig.config.server.USE_FIXED_RATIO_HPMP_UPDATE=ratio;}
    }
    @Test void ordinaryAmbientInitializationDoesNotRefillAfterConsumptionOrLevelGrowth() {
        var bot=mock(Character.class);when(bot.getJob()).thenReturn(Job.WARRIOR);when(bot.getLevel()).thenReturn(20);
        when(bot.getHp()).thenReturn(100);when(bot.getMp()).thenReturn(50);
        when(bot.getCurrentMaxHp()).thenReturn(100);when(bot.getCurrentMaxMp()).thenReturn(50);
        when(bot.getSkills()).thenReturn(Map.of());
        var equipped=new Inventory(bot,InventoryType.EQUIPPED,(byte)24);
        equipped.addItemFromDB(new Equip(1302000,(short)-11));
        var use=new Inventory(bot,InventoryType.USE,(byte)24);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        try(var skills=mockStatic(client.SkillFactory.class)) {
            CompanionBuild.initializeAmbient(bot);
            assertEquals(50,use.countById(2000002));assertEquals(50,use.countById(2000006));
            for(var item:List.copyOf(use.list())) use.removeItem(item.getPosition(),item.getQuantity(),false);
            CompanionBuild.initializeAmbient(bot);assertTrue(use.list().isEmpty());
            when(bot.getLevel()).thenReturn(21);CompanionBuild.initializeAmbient(bot);
            assertTrue(use.list().isEmpty());verify(bot,times(2)).changeRemainingAp(anyInt(),eq(true));
        }
    }
    @Test void easyBossRanksAppropriateLevelsWithoutFollowingHumanOverlevel() {
        assertTrue(BossSuitability.rank(20,20,1,100)<BossSuitability.rank(150,20,1,0));
        assertTrue(BossSuitability.rank(105,105,.9,100)<BossSuitability.rank(150,105,.35,0));
    }
    @BeforeAll static void initializeItemData() throws Exception {
        var connection=mock(java.sql.Connection.class,RETURNS_DEEP_STUBS);
        try(var database=mockStatic(tools.DatabaseConnection.class)) {
            database.when(tools.DatabaseConnection::getConnection).thenReturn(connection);
            ItemInformationProvider.getInstance();
        }
    }
    @Test void advancementBudgetsRespectActualLevelsAndFourthJobAward() {
        assertEquals(0,CompanionBuild.skillBudget(100,9));
        assertEquals(1,CompanionBuild.skillBudget(100,10));
        assertEquals(58,CompanionBuild.skillBudget(100,200));
        assertEquals(64,CompanionBuild.skillBudget(200,200));
        assertEquals(118,CompanionBuild.skillBudget(110,200));
        assertEquals(148,CompanionBuild.skillBudget(111,200));
        assertEquals(0,CompanionBuild.skillBudget(112,119));
        assertEquals(3,CompanionBuild.skillBudget(112,120));
    }
    @Test void incompatibleSkillsAndInflatedApAreRefusedBeforeEquipment() {
        var bot=mock(Character.class); when(bot.getJob()).thenReturn(Job.WARRIOR); when(bot.getLevel()).thenReturn(10);
        when(bot.getStr()).thenReturn(999);
        assertTrue(CompanionBuild.constraints(bot).contains("AP budget"));
        when(bot.getStr()).thenReturn(4); when(bot.getDex()).thenReturn(4); when(bot.getInt()).thenReturn(4); when(bot.getLuk()).thenReturn(4);
        var skill=mock(Skill.class); when(skill.getId()).thenReturn(2001001); when(skill.getMaxLevel()).thenReturn(20);
        when(bot.getSkills()).thenReturn(Map.of(skill,new Character.SkillEntry((byte)20,0,-1)));
        when(bot.getSkillLevel(skill)).thenReturn((byte)20);
        assertTrue(CompanionBuild.constraints(bot).contains("incompatible"));
        when(skill.getId()).thenReturn(1001004); when(bot.getSkillLevel(skill)).thenReturn((byte)20);
        assertTrue(CompanionBuild.constraints(bot).contains("SP budget"));
    }
    @Test void realEquipmentLevelStatsAndJobRestrictionsAreRequired() {
        var bot=mock(Character.class); when(bot.getJob()).thenReturn(Job.FIGHTER); when(bot.getLevel()).thenReturn(30);
        when(bot.getSkills()).thenReturn(Map.of()); var inventory=mock(Inventory.class);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(inventory);
        when(inventory.list()).thenReturn(List.of(new Equip(1302000,(short)-11)));
        var provider=mock(ItemInformationProvider.class); var requirements=new HashMap<String,Integer>();
        when(provider.getEquipStats(1302000)).thenReturn(requirements);
        try(var items=mockStatic(ItemInformationProvider.class)) {
            items.when(ItemInformationProvider::getInstance).thenReturn(provider);
            requirements.put("reqLevel",31); assertFalse(CompanionBuild.constraints(bot).isEmpty());
            requirements.put("reqLevel",30); requirements.put("reqSTR",50);
            assertFalse(CompanionBuild.constraints(bot).isEmpty()); when(bot.getTotalStr()).thenReturn(50);
            requirements.put("reqJob",2); assertFalse(CompanionBuild.constraints(bot).isEmpty());
            requirements.put("reqJob",1); assertEquals("",CompanionBuild.constraints(bot));
        }
    }
}

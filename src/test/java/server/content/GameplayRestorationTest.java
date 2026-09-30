package server.content;

import client.Character;
import client.Client;
import client.FamilyEntitlement;
import client.FamilyEntry;
import client.QuestStatus;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import net.server.channel.handlers.ItemRewardHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import provider.Data;
import provider.DataTool;
import provider.wz.XMLDomMapleData;
import server.ItemInformationProvider;
import server.partyquest.Pyramid;
import server.quest.Quest;
import server.quest.requirements.MonsterBookCardsRequirement;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import tools.DatabaseConnection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameplayRestorationTest {
    @org.junit.jupiter.api.BeforeAll static void itemData() throws Exception { GameplayTestData.initializeItems(); }
    private Character player(ContentState state) {var chr=mock(Character.class);when(chr.getContentState()).thenReturn(state);
        for(var type:client.inventory.InventoryType.values()) when(chr.getInventory(type)).thenReturn(mock(client.inventory.Inventory.class));
        return chr;}
    private Data wz(String file) throws Exception {
        Path p=Path.of(file);try(var in=new java.io.FileInputStream(p.toFile())){return new XMLDomMapleData(in,p.getParent());}
    }
    @Test void progressRoundTripsWithoutResettingCurrenciesAndTimers(){
        var s=new ContentState();s.set("egg.growth",1234);s.set("family.exp.until",123456789);s.set("survival.ticket.4001321",2);
        assertEquals(s.snapshot(),ContentState.decode(s.encode()).snapshot());
        assertThrows(IllegalArgumentException.class,()->ContentState.decode("v1\negg.growth=1\negg.growth=0\n"));
        assertThrows(IllegalArgumentException.class,()->ContentState.decode("v2\n"));
        assertThrows(IllegalArgumentException.class,()->ContentState.decode("v1\nx=bad"));
    }
    @Test void progressSaveBelongsToTheCallerTransaction() throws Exception {
        Connection con=mock(Connection.class);PreparedStatement ps=mock(PreparedStatement.class);when(con.prepareStatement(anyString())).thenReturn(ps);
        var s=new ContentState();s.set("egg.active",1);ContentStore.save(con,7,s);
        verify(ps).setInt(1,7);verify(ps).setString(2,s.encode());verify(ps).executeUpdate();verify(con,never()).commit();verify(con,never()).close();
        when(ps.executeUpdate()).thenThrow(new SQLException("failed"));assertThrows(SQLException.class,()->ContentStore.save(con,7,s));
    }
    @Test void corruptStoredProgressFailsClosed() throws Exception {
        Connection con=mock(Connection.class);PreparedStatement ps=mock(PreparedStatement.class);ResultSet rs=mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);when(ps.executeQuery()).thenReturn(rs);when(rs.next()).thenReturn(true);when(rs.getString(1)).thenReturn("broken");
        assertThrows(SQLException.class,()->ContentStore.load(con,7));
    }
    @Test void familyFractionalBonusSurvivesReloadAndExpiresAtDeadline(){
        var state=new ContentState();FamilyBenefits.grant(state,FamilyBenefits.benefit(FamilyEntitlement.SELF_EXP_1_5),1000);
        state=ContentState.decode(state.encode());
        assertEquals(1.5,FamilyBenefits.multiplier(state,true,1,1001));
        assertEquals(1,FamilyBenefits.multiplier(state,true,2,1001));
        assertEquals(1,FamilyBenefits.multiplier(state,false,1,1001));
        assertEquals(1,FamilyBenefits.multiplier(state,true,1,901000));
    }
    @Test void familyUpgradeDoesNotBorrowALongerWeakBonusDuration(){
        var s=new ContentState();FamilyBenefits.grant(s,new FamilyBenefits.Benefit(150,100,60,false),1000);
        FamilyBenefits.grant(s,new FamilyBenefits.Benefit(200,100,15,false),2000);
        assertEquals(902000,s.get("family.exp.until"));
        FamilyBenefits.grant(s,new FamilyBenefits.Benefit(150,100,60,false),3000);
        assertEquals(200,s.get("family.exp.percent"));assertEquals(902000,s.get("family.exp.until"));
    }
    @Test void failedFamilyEntitlementSaveDoesNotSpendOrGrant(){
        var s=new ContentState();var chr=player(s);var entry=mock(FamilyEntry.class);
        when(chr.getFamilyEntry()).thenReturn(entry);when(entry.getReputation()).thenReturn(5000);when(entry.purchaseBenefit(any())).thenReturn(false);
        assertTrue(FamilyBenefits.use(chr,FamilyEntitlement.SELF_EXP_1_5).contains("not spent"));
        assertEquals(0,s.get("family.exp.until"));
    }
    @Test void familySelfBenefitDebitsOriginalCostAndGrantsOnce(){
        var s=new ContentState();var chr=player(s);var entry=mock(FamilyEntry.class,RETURNS_DEEP_STUBS);when(chr.getFamilyEntry()).thenReturn(entry);
        when(entry.getFamily().getName()).thenReturn("Test");when(entry.getFamily().getMessage()).thenReturn("");
        when(entry.getReputation()).thenReturn(5000);when(entry.purchaseBenefit(any())).thenReturn(true);
        FamilyBenefits.use(chr,FamilyEntitlement.SELF_EXP_1_5);
        verify(entry).purchaseBenefit(FamilyEntitlement.SELF_EXP_1_5);assertEquals(150,s.get("family.exp.percent"));
        when(entry.isEntitlementUsed(any())).thenReturn(true);FamilyBenefits.use(chr,FamilyEntitlement.SELF_EXP_1_5);
        verify(entry,times(1)).purchaseBenefit(any());
    }
    @Test void familyBenefitCommitsUseAndCostTogether() throws Exception {
        var entry=new FamilyEntry(null,42,"Fixture",30,null);
        entry.setReputation(5000);entry.setTodaysRep(100);
        Connection con=mock(Connection.class);PreparedStatement rep=mock(PreparedStatement.class),usage=mock(PreparedStatement.class);
        when(con.getAutoCommit()).thenReturn(true);
        when(con.prepareStatement(anyString())).thenReturn(rep,usage);
        when(rep.executeUpdate()).thenReturn(1);when(usage.executeUpdate()).thenReturn(1);
        try(var database=mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenReturn(con);
            assertTrue(entry.purchaseBenefit(FamilyEntitlement.SELF_EXP_1_5));
        }
        verify(rep).setInt(1,4200);verify(rep).setInt(2,-700);
        verify(usage).setInt(1,FamilyEntitlement.SELF_EXP_1_5.ordinal());
        var order=inOrder(con,rep,usage);
        order.verify(con).setAutoCommit(false);order.verify(rep).executeUpdate();
        order.verify(usage).executeUpdate();order.verify(con).commit();
        assertEquals(4200,entry.getReputation());
        assertTrue(entry.isEntitlementUsed(FamilyEntitlement.SELF_EXP_1_5));
    }
    @Test void failedFamilyBenefitInsertRollsBackWithoutDebiting() throws Exception {
        var entry=new FamilyEntry(null,42,"Fixture",30,null);
        entry.setReputation(5000);
        Connection con=mock(Connection.class);PreparedStatement rep=mock(PreparedStatement.class),usage=mock(PreparedStatement.class);
        when(con.getAutoCommit()).thenReturn(true);
        when(con.prepareStatement(anyString())).thenReturn(rep,usage);
        when(rep.executeUpdate()).thenReturn(1);when(usage.executeUpdate()).thenThrow(new SQLException("insert failed"));
        try(var database=mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenReturn(con);
            assertFalse(entry.purchaseBenefit(FamilyEntitlement.SELF_EXP_1_5));
        }
        verify(con).rollback();verify(con,never()).commit();
        assertEquals(5000,entry.getReputation());
        assertFalse(entry.isEntitlementUsed(FamilyEntitlement.SELF_EXP_1_5));
    }
    @Test void familyReputationSnapshotStaysDirtyUntilCommittedAndUnchanged() throws Exception {
        var entry=new FamilyEntry(null,42,"Fixture",30,null);
        entry.setReputation(100);
        Connection con=mock(Connection.class);PreparedStatement first=mock(PreparedStatement.class),second=mock(PreparedStatement.class),third=mock(PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenReturn(first,second,third);
        when(first.executeUpdate()).thenReturn(1);when(second.executeUpdate()).thenReturn(1);when(third.executeUpdate()).thenReturn(1);
        var snapshot=entry.saveReputationSnapshot(con);
        con.rollback();
        assertNotNull(entry.saveReputationSnapshot(con));
        // A concurrent gain after the SQL write also keeps that snapshot dirty.
        entry.gainReputation(10,false);
        entry.savedSuccessfully(snapshot);
        var next=entry.saveReputationSnapshot(con);
        assertNotNull(next);
        assertEquals(110,next.reputation());
        verify(third).setInt(1,110);
        entry.savedSuccessfully(next);
        assertNull(entry.saveReputationSnapshot(con));
    }
    @Test void unchangedFamilyUpdateRequiresAnExistingRow() throws Exception {
        var entry=new FamilyEntry(null,42,"Fixture",30,null);entry.setReputation(100);
        Connection con=mock(Connection.class);PreparedStatement update=mock(PreparedStatement.class),exists=mock(PreparedStatement.class);
        ResultSet rows=mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenReturn(update,exists);
        when(update.executeUpdate()).thenReturn(0);when(exists.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true);
        assertNotNull(entry.saveReputationSnapshot(con));
        verify(exists).setInt(1,42);
    }
    @Test void petTrainingSkipsUnsummonedAlreadyTrainedAndOrdinaryPets(){
        var chr=mock(Character.class);var p=mock(Pet.class);when(chr.getPets()).thenReturn(new Pet[]{p,null,null});
        when(p.getItemId()).thenReturn(5000048);when(p.isSummoned()).thenReturn(true);when(p.getPetAttribute()).thenReturn(1|128);
        assertNull(SmartPets.untrained(chr,128));assertSame(p,SmartPets.untrained(chr,256));
        when(p.isSummoned()).thenReturn(false);assertNull(SmartPets.untrained(chr,256));
        when(p.isSummoned()).thenReturn(true);when(p.getItemId()).thenReturn(5000000);assertNull(SmartPets.untrained(chr,256));
    }
    @Test void exorcistRequiresAllFiveCardsFromActualWz() throws Exception {
        var data=wz("wz/Quest.wz/Check.img.xml").getChildByPath("29016/1/mbcard");
        var req=new MonsterBookCardsRequirement(null,data);var chr=mock(Character.class,RETURNS_DEEP_STUBS);Map<Integer,Integer> cards=new HashMap<>();
        when(chr.getMonsterBook().getCards()).thenReturn(cards);assertFalse(req.check(chr,1061011));
        for(Data row:data)cards.put(DataTool.getInt("id",row),1);
        assertTrue(req.check(chr,1061011));cards.remove(2382049);assertFalse(req.check(chr,1061011));
    }
    @Test void pqRankRequiresAttemptsRatioTimeAndEquipment(){
        var s=new ContentState();String k="KerningPQ";var r=PqRanks.RULES.get(k);
        s.set("pq."+k+".tries",100);s.set("pq."+k+".wins",89);s.set("pq."+k+".best",599000);
        assertFalse(PqRanks.qualifies(s,k,r,true));s.set("pq."+k+".wins",90);
        assertTrue(PqRanks.qualifies(s,k,r,true));assertFalse(PqRanks.qualifies(s,k,r,false));
        s.set("pq."+k+".best",600000);assertFalse(PqRanks.qualifies(s,k,r,true));
        assertEquals("Magatia",PqRanks.key("MagatiaPQ_A"));assertEquals("Magatia",PqRanks.key("MagatiaPQ_Z"));
    }
    @Test void timedChallengeAllowsThirtyDaysNotFortyThreeMinutes(){
        var s=new ContentState();s.set("medal.29400.start",1000);
        assertTrue(Medals.withinTrial(s,29400,1000+29L*86400000));
        assertFalse(Medals.withinTrial(s,29400,1000+30L*86400000));assertFalse(Medals.withinTrial(s,29400,999));
    }
    @Test void haircutCountsStylesButNotDyes(){
        var s=new ContentState();var chr=player(s);var qs=mock(QuestStatus.class);when(chr.getQuest(any(Quest.class))).thenReturn(qs);when(qs.getStatus()).thenReturn(QuestStatus.Status.STARTED);
        Medals.hairstyle(chr,30000,30001);assertEquals(0,s.get("medal.hair"));
        Medals.hairstyle(chr,30001,30101);assertEquals(1,s.get("medal.hair"));verify(chr).setQuestProgress(29020,29020,"1");
    }
    @Test void marketQuotesNeverAllowImmediateBuySellProfit(){
        for(long hour=1;hour<1000;hour++)for(int[] stock:SevenDayMarket.STOCK)for(int item:stock){
            assertTrue(SevenDayMarket.quote(1000,item,hour,true)>SevenDayMarket.quote(1000,item,hour,false));
            assertTrue(SevenDayMarket.quote(1000,item,hour,true)>=950);assertTrue(SevenDayMarket.quote(1000,item,hour,true)<=1150);
        }
    }
    @Test void forgedMarketOrderOrStaleQuoteNeverCharges(){
        var s=new ContentState();var chr=player(s);when(chr.getMapId()).thenReturn(680100000);
        SevenDayMarket.trade(chr,9209002,1142000,1,true,SevenDayMarket.hour());
        SevenDayMarket.trade(chr,9209002,2001000,-1,true,SevenDayMarket.hour());
        SevenDayMarket.trade(chr,9209002,2001000,1,true,SevenDayMarket.hour()-1);
        verify(chr,never()).gainMeso(anyInt(),anyBoolean());assertTrue(s.snapshot().isEmpty());
    }
    @Test void missingEggAndDoubleFeedingCannotConsumePowder(){
        var s=new ContentState();var chr=player(s);Client client=mock(Client.class);when(chr.getClient()).thenReturn(client);
        try(var inv=mockStatic(InventoryManipulator.class)){
            MarketEgg.feed(chr);inv.verifyNoInteractions();
            s.set("egg.active",1);s.set("egg.fed",MarketEgg.day());when(chr.haveItem(MarketEgg.EGG)).thenReturn(true);when(chr.haveItem(MarketEgg.POWDER)).thenReturn(true);
            MarketEgg.feed(chr);inv.verifyNoInteractions();assertEquals(0,s.get("egg.feeds"));
        }
    }
    @Test void eggCareGrantsOneProgressStepAndRetainsItAfterReload(){
        var s=new ContentState();var chr=player(s);s.set("egg.active",1);when(chr.haveItem(MarketEgg.EGG)).thenReturn(true);when(chr.haveItem(MarketEgg.POWDER)).thenReturn(true);
        try(var inv=mockStatic(InventoryManipulator.class)){
            MarketEgg.feed(chr);MarketEgg.feed(chr);assertEquals(1,s.get("egg.feeds"));assertEquals(600,s.get("egg.growth"));
            assertEquals(600,ContentState.decode(s.encode()).get("egg.growth"));
            inv.verify(()->InventoryManipulator.removeById(null,client.inventory.InventoryType.ETC,MarketEgg.POWDER,1,true,false),times(1));
        }
    }
    @Test void survivalSkillsAreCappedAndResultsCannotPayTwice(){
        var s=new SurvivalState();assertFalse(s.useSkill());
        for(int i=0;i<3000;i++)s.hit(false);
        assertEquals(6,s.skillUses());for(int i=0;i<6;i++)assertTrue(s.useSkill());assertFalse(s.useSkill());
        for(int i=0;i<1000;i++)s.hit(false);assertEquals(0,s.skillUses());
        assertTrue(s.claim());assertFalse(s.claim());assertEquals(0,s.rank(true,false));assertEquals(4,s.rank(false,false));
    }
    @Test void survivalBuffMilestonesAreAppliedOnlyOnce(){
        var s=new SurvivalState();for(int i=0;i<249;i++)s.hit(false);assertEquals(0,s.nextBuff(false));s.hit(false);
        assertEquals(2022585,s.nextBuff(false));assertEquals(0,s.nextBuff(false));for(int i=0;i<250;i++)s.hit(false);
        assertEquals(2022586,s.nextBuff(false));assertEquals(0,s.nextBuff(false));
    }
    @Test void fullInventoryRetainsEarnedSurvivalPassAndRepeatClaimIsEmpty(){
        var s=new ContentState();var chr=player(s);s.set("survival.ticket.4001321",1);
        try(var inv=mockStatic(InventoryManipulator.class)){
            Pyramid.collectTickets(chr,true);assertEquals(1,s.get("survival.ticket.4001321"));
            inv.when(()->InventoryManipulator.checkSpace(null,4001321,1,"")).thenReturn(true);
            inv.when(()->InventoryManipulator.addById(null,4001321,(short)1)).thenReturn(true);
            Pyramid.collectTickets(chr,true);Pyramid.collectTickets(chr,true);assertEquals(0,s.get("survival.ticket.4001321"));
            inv.verify(()->InventoryManipulator.addById(null,4001321,(short)1),times(1));
        }
    }
    @ParameterizedTest @ValueSource(ints={2022613,2022615,2022618})
    void chestEveryWeightedOutcomeIsReachableWithItsExactWzProbability(int item) throws Exception {
        Data data=wz("wz/Item.wz/Consume/0202.img.xml").getChildByPath("0"+item+"/reward");assertNotNull(data);
        List<ItemInformationProvider.RewardItem> rewards=new ArrayList<>();int total=0;
        for(Data row:data){var r=new ItemInformationProvider.RewardItem();r.itemid=DataTool.getInt("item",row);r.prob=DataTool.getInt("prob",row);rewards.add(r);total+=r.prob;}
        Map<Integer,Integer> counts=new HashMap<>();for(int roll=0;roll<total;roll++){var r=ItemRewardHandler.selectReward(rewards,roll);assertNotNull(r);counts.merge(r.itemid,1,Integer::sum);}
        var loaded=ItemInformationProvider.getInstance().getItemReward(item);
        assertEquals(total,loaded.getLeft());assertEquals(rewards.size(),loaded.getRight().size());
        for(int i=0;i<rewards.size();i++)assertEquals(rewards.get(i).prob,loaded.getRight().get(i).prob);
        Map<Integer,Integer> expected=new HashMap<>();for(var r:rewards)expected.merge(r.itemid,r.prob,Integer::sum);
        assertEquals(expected,counts);assertNull(ItemRewardHandler.selectReward(rewards,total));
    }

    @Test void exchangeLocksUseOneOrderAndReleaseInReverseEvenOnFailure(){
        var chr=player(new ContentState());var equip=chr.getInventory(client.inventory.InventoryType.EQUIP);var use=chr.getInventory(client.inventory.InventoryType.USE);
        try(var locks=InventoryLocks.acquire(chr,client.inventory.InventoryType.USE,client.inventory.InventoryType.EQUIP,client.inventory.InventoryType.USE)){}
        var order=inOrder(equip,use);order.verify(equip).lockInventory();order.verify(use).lockInventory();order.verify(use).unlockInventory();order.verify(equip).unlockInventory();
        reset(equip,use);when(chr.getInventory(client.inventory.InventoryType.USE)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,()->InventoryLocks.acquire(chr,client.inventory.InventoryType.EQUIP,client.inventory.InventoryType.USE));verify(equip).unlockInventory();
    }
    @Test void failedEggRewardDeliveryKeepsEggAndReturnAllowance(){
        var s=new ContentState();var chr=player(s);s.set("egg.active",1);s.set("egg.growth",3000);s.set("egg.feeds",5);when(chr.haveItem(MarketEgg.EGG)).thenReturn(true);
        try(var inv=mockStatic(InventoryManipulator.class)){
            inv.when(()->InventoryManipulator.checkSpace(null,MarketEgg.HAT,1,"")).thenReturn(true);
            MarketEgg.claim(chr);assertEquals(1,s.get("egg.active"));assertEquals(0,s.get("egg.claimed"));verify(chr,never()).gainMeso(anyInt(),anyBoolean());
            inv.verify(()->InventoryManipulator.removeById(null,client.inventory.InventoryType.ETC,MarketEgg.EGG,1,true,false),never());
        }
    }
    @Test void marketOnlyPaysForOwnedGoodsAndRetainedPurchaseAllowance(){
        var s=new ContentState();var chr=player(s);when(chr.getMapId()).thenReturn(680100003);s.set("market.stock.2001000",1);
        try(var inv=mockStatic(InventoryManipulator.class)){
            SevenDayMarket.trade(chr,9209002,2001000,1,false,SevenDayMarket.hour());verify(chr,never()).gainMeso(anyInt(),anyBoolean());
            when(chr.getInventory(client.inventory.InventoryType.USE).countById(2001000)).thenReturn(1);
            SevenDayMarket.trade(chr,9209002,2001000,1,false,SevenDayMarket.hour());assertEquals(0,s.get("market.stock.2001000"));
            SevenDayMarket.trade(chr,9209002,2001000,1,false,SevenDayMarket.hour());verify(chr,times(1)).gainMeso(anyInt(),eq(false));
            inv.verify(()->InventoryManipulator.removeById(null,client.inventory.InventoryType.USE,2001000,1,true,false),times(1));
        }
    }
    @ParameterizedTest @ValueSource(ints={4660,4661})
    void petActionsReadTheActualWzShortFlagAndTeachAnEligiblePet(int id) throws Exception {
        var data=wz("wz/Quest.wz/Act.img.xml").getChildByPath(id+"/1/petskill");
        var action=new server.quest.actions.PetSkillAction(Quest.getInstance(id),data);var chr=player(new ContentState());var pet=mock(Pet.class);
        when(chr.getPets()).thenReturn(new Pet[]{pet,null,null});when(pet.getItemId()).thenReturn(5000048);when(pet.isSummoned()).thenReturn(true);
        assertTrue(action.check(chr,null));action.run(chr,null);verify(pet).addPetAttribute(chr,id==4660?Pet.PetAttribute.RECALL:Pet.PetAttribute.AUTO_SPEAK);
    }
}

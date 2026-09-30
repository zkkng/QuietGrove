package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.inventory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import server.ItemInformationProvider;
import server.life.Monster;
import server.maps.*;
import java.awt.Point;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossEconomyTest {
    @BeforeAll static void initializeItemData() throws Exception {
        var connection=mock(java.sql.Connection.class,RETURNS_DEEP_STUBS);
        try(var database=mockStatic(tools.DatabaseConnection.class)) {
            database.when(tools.DatabaseConnection::getConnection).thenReturn(connection);
            ItemInformationProvider.getInstance();
        }
    }
    @Test void sharedLootHonorsRealPartyInventoryRangeQuestAndOneTimePickup() {
        var bot=mock(Character.class); var map=mock(MapleMap.class); var monster=mock(Monster.class);
        when(bot.getId()).thenReturn(20061); when(bot.getPartyId()).thenReturn(7); when(bot.isAlive()).thenReturn(true);
        when(bot.getMap()).thenReturn(map); when(bot.getPosition()).thenReturn(new Point()); when(monster.getEncounterId()).thenReturn(112L);
        var drop=new MapItem(new Item(4000000,(short)0,(short)1),new Point(20,0),monster,bot,null,(byte)1,false);
        drop.setObjectId(777); when(map.getMapObject(777)).thenReturn(drop);
        var inventory=new Inventory(bot,InventoryType.ETC,(byte)1); when(bot.getInventory(InventoryType.ETC)).thenReturn(inventory);
        var items=mock(ItemInformationProvider.class);
        when(items.getSlotMaxForCharacter(bot,4000000)).thenReturn((short)100);
        doAnswer(invocation -> {drop.setPickedUp(true);return null;}).when(map).pickItemDrop(any(),eq(drop));
        clearInvocations(bot); // MapItem construction stores its real owner's client; pickup must not use it.
        try(var providers=mockStatic(ItemInformationProvider.class)) {
            providers.when(ItemInformationProvider::getInstance).thenReturn(items);
            BossLoot.preference(112,"for me",7); assertFalse(BossLoot.pickup(bot,drop));
            BossLoot.preference(112,"shared",7); when(bot.getPartyId()).thenReturn(8); assertFalse(BossLoot.pickup(bot,drop));
            when(bot.getPartyId()).thenReturn(7); assertFalse(BossLoot.pickup(bot,drop)); // Quest eligibility is real.
            when(bot.needQuestItem(-1,4000000)).thenReturn(true);
            when(bot.getPosition()).thenReturn(new Point(1000,0)); assertFalse(BossLoot.pickup(bot,drop));
            when(bot.getPosition()).thenReturn(new Point());
            assertTrue(BossLoot.pickup(bot,drop)); assertFalse(BossLoot.pickup(bot,drop));
            assertEquals(1,inventory.countById(4000000)); verify(map,times(1)).pickItemDrop(any(),eq(drop));
            verify(bot,never()).getClient();
        }
    }
    @Test void fullInventoryAndRestrictedDuplicateLeaveDropOnGround() {
        var bot=mock(Character.class); var map=mock(MapleMap.class); var monster=mock(Monster.class);
        when(bot.getId()).thenReturn(20062); when(bot.getPartyId()).thenReturn(9); when(bot.isAlive()).thenReturn(true);
        when(bot.getMap()).thenReturn(map); when(bot.getPosition()).thenReturn(new Point()); when(monster.getEncounterId()).thenReturn(113L);
        var inventory=new Inventory(bot,InventoryType.ETC,(byte)1); inventory.addItem(new Item(4000001,(short)0,(short)1));
        when(bot.getInventory(InventoryType.ETC)).thenReturn(inventory);
        var drop=new MapItem(new Item(4000000,(short)0,(short)1),new Point(),monster,bot,null,(byte)1,false);
        drop.setObjectId(778); when(map.getMapObject(778)).thenReturn(drop); when(bot.needQuestItem(-1,4000000)).thenReturn(true);
        var items=mock(ItemInformationProvider.class);
        when(items.getSlotMaxForCharacter(bot,4000000)).thenReturn((short)100);
        try(var providers=mockStatic(ItemInformationProvider.class)) {
            providers.when(ItemInformationProvider::getInstance).thenReturn(items);
            BossLoot.preference(113,"shared",9);
            assertFalse(BossLoot.pickup(bot,drop)); assertFalse(drop.isPickedUp());
            inventory.removeItem((short)1,(short)1,false); when(items.isPickupRestricted(4000000)).thenReturn(true);
            when(bot.haveItem(4000000)).thenReturn(true);
            assertFalse(BossLoot.pickup(bot,drop)); verify(map,never()).pickItemDrop(any(),any());
            assertEquals(0,inventory.countById(4000000));
        }
    }
    @Test void stackCapacityFailureIsAtomicAndSuccessfulPickupUsesExistingSlot() {
        var bot=mock(Character.class); var map=mock(MapleMap.class); var monster=mock(Monster.class);
        when(bot.getId()).thenReturn(20063); when(bot.getPartyId()).thenReturn(10); when(bot.isAlive()).thenReturn(true);
        when(bot.getMap()).thenReturn(map); when(bot.getPosition()).thenReturn(new Point()); when(monster.getEncounterId()).thenReturn(114L);
        var inventory=new Inventory(bot,InventoryType.ETC,(byte)1); inventory.addItem(new Item(4000000,(short)0,(short)90));
        when(bot.getInventory(InventoryType.ETC)).thenReturn(inventory); when(bot.needQuestItem(-1,4000000)).thenReturn(true);
        var drop=new MapItem(new Item(4000000,(short)0,(short)11),new Point(),monster,bot,null,(byte)1,false);
        drop.setObjectId(779); when(map.getMapObject(779)).thenReturn(drop);
        var items=mock(ItemInformationProvider.class); when(items.getSlotMaxForCharacter(bot,4000000)).thenReturn((short)100);
        doAnswer(i->{drop.setPickedUp(true);return null;}).when(map).pickItemDrop(any(),eq(drop));
        try(var providers=mockStatic(ItemInformationProvider.class)) {
            providers.when(ItemInformationProvider::getInstance).thenReturn(items); BossLoot.preference(114,"shared",10);
            assertFalse(BossLoot.pickup(bot,drop)); assertEquals(90,inventory.countById(4000000)); assertFalse(drop.isPickedUp());
            drop.getItem().setQuantity((short)10); assertTrue(BossLoot.pickup(bot,drop));
            assertEquals(100,inventory.countById(4000000)); assertEquals(1,inventory.list().size());
            assertFalse(BossLoot.pickup(bot,drop)); verify(map,times(1)).pickItemDrop(any(),eq(drop));
        }
    }
    @Test void samePartyHumanContributionAndCancellationCannotFabricateRewards() {
        var d=BossRegistry.get("mano"); var h=new BossObjective(5,100,new CompanionTaskService.PartyKey(0,7),d.gatherMap(),d,
                BossObjective.Mode.HELP_CURRENT_ENCOUNTER,1000);
        var id=new BossObjective.Identity(0,1,4,17);
        h.partyDamage(id,2220000,101,50); assertTrue(h.removed(id,2220000,true));
        assertEquals(BossObjective.Outcome.KILLED_WITH_CONTRIBUTION,h.outcome()); assertEquals(50L,h.contributions().get(101));
        assertFalse(h.removed(id,2220000,true)); assertFalse(h.finish(BossObjective.Outcome.PLAYER_CANCELLED,"late"));
    }
}

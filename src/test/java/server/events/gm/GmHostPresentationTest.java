package server.events.gm;

import client.Character;
import client.Client;
import client.inventory.*;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import tools.PacketCreator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GmHostPresentationTest {
    @BeforeAll static void initializeItemData() throws Exception {
        var connection=mock(java.sql.Connection.class,RETURNS_DEEP_STUBS);
        try(var database=mockStatic(tools.DatabaseConnection.class)) {
            database.when(tools.DatabaseConnection::getConnection).thenReturn(connection);
            ItemInformationProvider.getInstance();
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Character actor=mock(Character.class);
        final Client client=mock(Client.class);
        final Inventory gear=new Inventory(actor,InventoryType.EQUIPPED,(byte)127);
        final CompanionTaskService tasks=new CompanionTaskService(()->1000L,30);
        final org.mockito.MockedStatic<CompanionTaskService> registries=mockStatic(CompanionTaskService.class);
        final org.mockito.MockedStatic<BotHelpers> bots=mockStatic(BotHelpers.class);
        CompanionTaskService.EventLease lease;
        Fixture(CompanionTaskService.EventRole role,boolean commit) {
            when(actor.getId()).thenReturn(24001);when(actor.getName()).thenReturn("Adam");
            when(actor.getClient()).thenReturn(client);when(client.getChannel()).thenReturn(1);
            when(actor.getInventory(InventoryType.EQUIPPED)).thenReturn(gear);
            gear.addItemFromDB(new Equip(1002000,(short)-1));
            gear.addItemFromDB(new Equip(1302000,(short)-111));
            registries.when(CompanionTaskService::shared).thenReturn(tasks);
            bots.when(()->BotHelpers.isBot(actor)).thenReturn(true);
            lease=tasks.reserveEvent("host-test",24001,0,1,role,2000,
                    new CompanionTaskService.PriorActivity("SOCIAL_BOT",100000000,100000000)).orElseThrow();
            if(commit) lease=tasks.commitEvent(lease).orElseThrow();
        }
        public void close() {GmHostPresentation.restore(actor,lease.generation());bots.close();registries.close();}
    }
    @Test void realWizetUniformAndPrefixedNameLeaveSavedEquipmentAndAccountAuthorityIntact() {
        try(var f=new Fixture(CompanionTaskService.EventRole.HOST,true)) {
            Item original=f.gear.getItem((short)-1);
            assertTrue(GmHostPresentation.activate(f.actor,f.lease));
            assertEquals("[GM]Adam",GmHostPresentation.displayName(f.actor));
            when(f.actor.getName()).thenReturn("[GM][GM]Adam");
            assertTrue(GmHostPresentation.restore(f.actor,f.lease.generation()));
            assertTrue(GmHostPresentation.activate(f.actor,f.lease));
            assertEquals("[GM]Adam",GmHostPresentation.displayName(f.actor));
            when(f.actor.getName()).thenReturn("Adam");
            assertEquals(List.of(1002140,1042003,1062007,1322013),GmHostPresentation.equipment(f.actor).stream().map(Item::getItemId).toList());
            assertEquals(List.of((short)-1,(short)-5,(short)-6,(short)-11),GmHostPresentation.equipment(f.actor).stream().map(Item::getPosition).toList());
            assertEquals("Wizet Invincible Hat",ItemInformationProvider.getInstance().getName(1002140));
            assertSame(original,f.gear.getItem((short)-1));assertEquals(2,f.gear.list().size());
            assertEquals("Adam",f.actor.getName());assertEquals(0,f.actor.gmLevel());
            verify(f.actor,never()).setName(anyString());verify(f.actor,never()).setGM(anyInt());
            verify(f.actor,never()).setGMLevel(anyInt());verify(f.actor,never()).equipChanged();
            assertTrue(GmHostPresentation.restore(f.actor,f.lease.generation()));
            assertFalse(GmHostPresentation.restore(f.actor,f.lease.generation()));
            assertEquals("Adam",GmHostPresentation.displayName(f.actor));assertNull(GmHostPresentation.equipment(f.actor));
        }
    }
    @Test void pendingParticipantHumanOrWrongChannelCannotAcquireStaffPresentation() {
        try(var f=new Fixture(CompanionTaskService.EventRole.HOST,false)) {assertFalse(GmHostPresentation.activate(f.actor,f.lease));}
        try(var f=new Fixture(CompanionTaskService.EventRole.PARTICIPANT,true)) {assertFalse(GmHostPresentation.activate(f.actor,f.lease));}
        try(var f=new Fixture(CompanionTaskService.EventRole.HOST,true)) {
            f.bots.when(()->BotHelpers.isBot(f.actor)).thenReturn(false);
            assertFalse(GmHostPresentation.activate(f.actor,f.lease));
            f.bots.when(()->BotHelpers.isBot(f.actor)).thenReturn(true);when(f.client.getChannel()).thenReturn(2);
            assertFalse(GmHostPresentation.activate(f.actor,f.lease));
        }
    }
    @Test void releasedLeaseHidesPresentationAndLateOldRestoreCannotClearANewHostGeneration() {
        try(var f=new Fixture(CompanionTaskService.EventRole.HOST,true)) {
            assertTrue(GmHostPresentation.activate(f.actor,f.lease));long old=f.lease.generation();
            f.tasks.releaseEvent(f.actor.getId(),old);
            assertEquals("Adam",GmHostPresentation.displayName(f.actor));assertNull(GmHostPresentation.equipment(f.actor));
            var pending=f.tasks.reserveEvent("new-host",24001,0,1,CompanionTaskService.EventRole.HOST,2000,
                    f.lease.prior()).orElseThrow();
            f.lease=f.tasks.commitEvent(pending).orElseThrow();
            assertTrue(GmHostPresentation.activate(f.actor,f.lease));
            assertFalse(GmHostPresentation.restore(f.actor,old));assertEquals("[GM]Adam",GmHostPresentation.displayName(f.actor));
        }
    }
    @Test void actualAvatarPacketContainsTheFullUniformWithoutTheOldCashWeaponMask() throws Exception {
        try(var f=new Fixture(CompanionTaskService.EventRole.HOST,true)) {
            assertTrue(GmHostPresentation.activate(f.actor,f.lease));
            var packet=OutPacket.create(SendOpcode.SPAWN_PLAYER);
            var encode=PacketCreator.class.getDeclaredMethod("addCharEquips",OutPacket.class,Character.class);
            encode.setAccessible(true);encode.invoke(null,packet,f.actor);
            var bytes=ByteBuffer.wrap(packet.getBytes()).order(ByteOrder.LITTLE_ENDIAN);bytes.getShort();
            for(var expected:new int[][]{{1,1002140},{5,1042003},{6,1062007},{11,1322013}}) {
                assertEquals(expected[0],Byte.toUnsignedInt(bytes.get()));assertEquals(expected[1],bytes.getInt());
            }
            assertEquals(255,Byte.toUnsignedInt(bytes.get()));assertEquals(255,Byte.toUnsignedInt(bytes.get()));
            assertEquals(0,bytes.getInt());assertEquals(1302000,f.gear.getItem((short)-111).getItemId());
        }
    }
}

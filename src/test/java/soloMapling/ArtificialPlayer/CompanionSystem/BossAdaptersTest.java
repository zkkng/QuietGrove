package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.inventory.*;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.opcodes.SendOpcode;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import scripting.event.*;
import server.life.*;
import server.maps.*;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import tools.PacketCreator;
import java.awt.Point;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossAdaptersTest {
    @Test void boatBoardingUsesActualTicketAndPriceOnlyDuringOpenGate() {
        var bot=mock(Character.class); var human=mock(Character.class);
        var source=mock(MapleMap.class); var waiting=mock(MapleMap.class); var channel=mock(Channel.class);
        var em=mock(EventManager.class); var scripts=mock(EventScriptManager.class); var factory=mock(MapManager.class);
        var seller=mock(NPC.class); var officer=mock(NPC.class); var arrival=mock(Portal.class);
        var inventory=new Inventory(bot,InventoryType.ETC,(byte)2);
        when(bot.getMap()).thenReturn(source); when(bot.getMapId()).thenReturn(101000300);
        when(bot.isAlive()).thenReturn(true); when(human.isAlive()).thenReturn(true);
        when(bot.getPosition()).thenReturn(new Point()); when(bot.getMeso()).thenReturn(5000);
        when(bot.getInventory(InventoryType.ETC)).thenReturn(inventory);
        when(human.getMap()).thenReturn(waiting); when(human.getMapId()).thenReturn(101000301);
        when(source.getChannelServer()).thenReturn(channel); when(waiting.getChannelServer()).thenReturn(channel);
        when(channel.getEventSM()).thenReturn(scripts); when(scripts.getEventManager("Boats")).thenReturn(em);
        when(source.getNPCById(1032007)).thenReturn(seller); when(source.getNPCById(1032008)).thenReturn(officer);
        when(seller.getPosition()).thenReturn(new Point()); when(officer.getPosition()).thenReturn(new Point());
        when(channel.getMapFactory()).thenReturn(factory); when(factory.getMap(101000301)).thenReturn(waiting);
        when(waiting.getPortal(0)).thenReturn(arrival);
        try(var movement=mockStatic(GCMovement.class)) {
            assertFalse(BossAccess.tick(bot,human,BossRegistry.get("boat-balrog")));
            verify(bot,never()).gainMeso(anyInt(),anyBoolean());
            when(em.getProperty("entry")).thenReturn("true");
            assertTrue(BossAccess.tick(bot,human,BossRegistry.get("boat-balrog")));
            assertEquals(0,inventory.countById(4031045));
            verify(bot).gainMeso(-5000,false); verify(bot).changeMap(waiting,arrival);
            when(bot.getMeso()).thenReturn(4999);
            assertFalse(BossAccess.tick(bot,human,BossRegistry.get("boat-balrog")));
            verify(bot,times(1)).gainMeso(-5000,false);
        }
    }
    @Test void instanceAdmissionRequiresExactScriptAndOwnedMap() {
        var actor=mock(Character.class); var arena=mock(MapleMap.class);
        var eim=mock(EventInstanceManager.class); var other=mock(EventInstanceManager.class); var em=mock(EventManager.class);
        when(actor.getMap()).thenReturn(arena); when(actor.getMapId()).thenReturn(280030000);
        when(actor.getEventInstance()).thenReturn(eim); when(eim.getEm()).thenReturn(em);
        when(em.getName()).thenReturn("ZakumBattle"); when(arena.getEventInstance()).thenReturn(eim);
        assertTrue(BossAccess.mapAllowed(actor,BossRegistry.get("zakum")));
        when(arena.getEventInstance()).thenReturn(other);
        assertFalse(BossAccess.mapAllowed(actor,BossRegistry.get("zakum")));
        when(arena.getEventInstance()).thenReturn(eim); when(em.getName()).thenReturn("HorntailBattle");
        assertFalse(BossAccess.mapAllowed(actor,BossRegistry.get("zakum")));
    }
    @Test void horntailStageDoorRequiresClearedHeadAndPhysicalArrivalInSameInstance() {
        var bot=mock(Character.class); var leader=mock(Character.class); var source=mock(MapleMap.class); var next=mock(MapleMap.class);
        var eim=mock(EventInstanceManager.class); var portal=mock(Portal.class); var arrival=mock(Portal.class);
        when(bot.getMap()).thenReturn(source); when(bot.getMapId()).thenReturn(240060000);
        when(bot.isAlive()).thenReturn(true); when(leader.isAlive()).thenReturn(true);
        when(bot.getPosition()).thenReturn(new Point()); when(bot.getEventInstance()).thenReturn(eim);
        when(leader.getEventInstance()).thenReturn(eim); when(leader.getMapId()).thenReturn(240060100);
        when(source.getPortals()).thenReturn(List.of(portal)); when(portal.getScriptName()).thenReturn("hontale_BR");
        when(portal.getPortalStatus()).thenReturn(true); when(portal.getPosition()).thenReturn(new Point(200,0));
        when(eim.getMapInstance(240060100)).thenReturn(next); when(next.getPortal("0")).thenReturn(arrival);
        try(var movement=mockStatic(GCMovement.class)) {
            assertFalse(BossAccess.tick(bot,leader,BossRegistry.get("horntail")));
            when(eim.getIntProperty("defeatedHead")).thenReturn(1);
            assertTrue(BossAccess.tick(bot,leader,BossRegistry.get("horntail")));
            verify(bot,never()).changeMap(any(MapleMap.class),any(Portal.class));
            movement.verify(() -> GCMovement.move(bot,200,0));
            when(bot.getPosition()).thenReturn(new Point(200,0));
            assertTrue(BossAccess.tick(bot,leader,BossRegistry.get("horntail")));
            verify(bot).changeMap(next,arrival);
        }
    }
    @Test void monsterActionUsesV83EnvelopeAndCriticalDisplayRetainsExactDamage() {
        var monster=mock(Monster.class); when(monster.getObjectId()).thenReturn(321);
        when(monster.getPosition()).thenReturn(new Point(-20,44)); when(monster.getFh()).thenReturn(7);
        var packet=new ByteBufInPacket(Unpooled.wrappedBuffer(PacketCreator.serverMonsterAction(monster,22,true,120,3).getBytes()));
        assertEquals(SendOpcode.MOVE_MONSTER.getValue(),packet.readShort()); assertEquals(321,packet.readInt());
        assertEquals(0,packet.readByte()); assertEquals(0,packet.readByte()); assertEquals(45,packet.readByte());
        assertEquals(120,packet.readByte()); assertEquals(3,packet.readByte()); assertEquals(0,packet.readShort());
        assertEquals(-20,packet.readShort()); assertEquals(44,packet.readShort()); assertEquals(1,packet.readByte());
        assertEquals(0,packet.readByte()); assertEquals(-20,packet.readShort()); assertEquals(44,packet.readShort());
        assertEquals(0,packet.readShort()); assertEquals(0,packet.readShort()); assertEquals(7,packet.readShort());
        assertEquals(45,packet.readByte()); assertEquals(500,packet.readShort()); assertEquals(0,packet.available());
        for(int damage:List.of(1,1234,999999)) assertEquals(damage,BotAttackData.decodeDamageLine(BotAttackData.encodeCritLine(damage)));
    }
}

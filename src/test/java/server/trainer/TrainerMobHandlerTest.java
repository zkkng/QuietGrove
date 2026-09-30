package server.trainer;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import net.packet.ByteBufInPacket;
import net.packet.Packet;
import net.server.channel.handlers.MoveLifeHandler;
import net.server.channel.handlers.TakeDamageHandler;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.MapObjectType;
import tools.PacketCreator;
import java.awt.Point;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TrainerMobHandlerTest {
    private final Character actor = mock(Character.class);
    private final Client client = mock(Client.class);
    private final MapleMap map = mock(MapleMap.class);
    private final Monster mob = mock(Monster.class);
    private final TrainerService trainer = mock(TrainerService.class);
    private void setup() {
        when(client.getPlayer()).thenReturn(actor); when(actor.getMap()).thenReturn(map);
        when(map.getMapObject(9)).thenReturn(mob); when(mob.getType()).thenReturn(MapObjectType.MONSTER);
        when(mob.getPosition()).thenReturn(new Point(10,20)); when(mob.getMp()).thenReturn(100);
    }
    @Test void unauthorizedControllerCannotReachTrainerOrMonsterEffects() {
        setup(); when(mob.aggroMoveLifeUpdate(actor)).thenReturn(null);
        ByteBuf bytes = Unpooled.buffer().writeIntLE(9).writeShortLE(7);
        try (var singleton = mockStatic(TrainerService.class)) {
            singleton.when(TrainerService::getInstance).thenReturn(trainer);
            new MoveLifeHandler().handlePacket(new ByteBufInPacket(bytes),client);
            verifyNoInteractions(trainer); verify(client,never()).sendPacket(any());
        } finally { bytes.release(); }
    }
    @Test void freezeAcknowledgesMoveWithoutReadingOrExecutingAttackBody() {
        setup(); when(mob.aggroMoveLifeUpdate(actor)).thenReturn(true); when(trainer.monsterFrozen(mob)).thenReturn(true);
        ByteBuf bytes = Unpooled.buffer().writeIntLE(9).writeShortLE(7);
        try (var singleton = mockStatic(TrainerService.class); var packets = mockStatic(PacketCreator.class)) {
            singleton.when(TrainerService::getInstance).thenReturn(trainer);
            Packet ack = mock(Packet.class);
            packets.when(() -> PacketCreator.moveMonsterResponse(9,(short)7,100,true)).thenReturn(ack);
            new MoveLifeHandler().handlePacket(new ByteBufInPacket(bytes),client);
            verify(client).sendPacket(ack); verify(mob).resetMobPosition(new Point(10,20));
            verify(mob,never()).canUseAttack(anyInt(),anyBoolean()); verify(mob,never()).hasSkill(anyInt(),anyInt());
            verify(map,never()).moveMonster(any(),any());
        } finally { bytes.release(); }
    }
    @Test void disarmSuppressesSkillAndNextCastButRetainsActualWalkingUpdate() {
        setup(); when(mob.aggroMoveLifeUpdate(actor)).thenReturn(true); when(trainer.monsterDisarmed(mob)).thenReturn(true);
        ByteBuf bytes = Unpooled.buffer().writeIntLE(9).writeShortLE(7)
                .writeByte(0).writeByte(42).writeByte(100).writeByte(1).writeShortLE(0).writeZero(8)
                .writeByte(0).writeIntLE(0).writeShortLE(10).writeShortLE(20)
                .writeByte(1).writeByte(0).writeShortLE(123).writeShortLE(45)
                .writeShortLE(0).writeShortLE(0).writeShortLE(1).writeByte(2).writeShortLE(80);
        try (var singleton = mockStatic(TrainerService.class); var packets = mockStatic(PacketCreator.class)) {
            singleton.when(TrainerService::getInstance).thenReturn(trainer);
            new MoveLifeHandler().handlePacket(new ByteBufInPacket(bytes),client);
            verify(mob).setPosition(new Point(123,43)); verify(map).moveMonster(eq(mob),any());
            verify(mob,never()).hasSkill(anyInt(),anyInt()); verify(mob,never()).canUseAttack(anyInt(),anyBoolean());
            verify(mob,never()).getRandomSkill();
        } finally { bytes.release(); }
    }
    @Test void disarmedValidatedAttackerCannotConsumeInventoryOrHp() {
        setup(); when(mob.getId()).thenReturn(100); when(trainer.monsterDisarmed(mob)).thenReturn(true);
        ByteBuf bytes = Unpooled.buffer().writeIntLE(0).writeByte(0).writeByte(0).writeIntLE(500).writeIntLE(100).writeIntLE(9);
        try (var singleton = mockStatic(TrainerService.class)) {
            singleton.when(TrainerService::getInstance).thenReturn(trainer);
            new TakeDamageHandler().handlePacket(new ByteBufInPacket(bytes),client);
            verify(trainer).monsterDisarmed(mob); verify(mob,never()).getStats();
            verify(actor,never()).addHP(anyInt()); verify(actor,never()).getInventory(any());
        } finally { bytes.release(); }
    }
}

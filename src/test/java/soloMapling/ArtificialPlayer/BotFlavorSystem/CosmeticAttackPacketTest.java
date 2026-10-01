package soloMapling.ArtificialPlayer.BotFlavorSystem;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.WeaponType;
import constants.skills.Hermit;
import net.opcodes.SendOpcode;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.*;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CosmeticAttackPacketTest {
    @Test void avengerFlavorUsesRangedPacketWithProjectileAndNoDamage() throws Exception {
        var bot=mock(Character.class); var map=mock(MapleMap.class); var skill=mock(Skill.class);
        when(bot.getMap()).thenReturn(map); when(bot.getId()).thenReturn(22111); when(bot.getJob()).thenReturn(Job.HERMIT);
        when(skill.getMaxLevel()).thenReturn(30);
        var profile=BotAttackProfile.rangedAoe(Hermit.AVENGER,1);
        try(var attacks=mockStatic(BotAttack.class,CALLS_REAL_METHODS);
            var config=mockStatic(BotAttackConfig.class); var skills=mockStatic(SkillFactory.class);
            var data=mockStatic(BotAttackData.class,CALLS_REAL_METHODS);
            var movement=mockStatic(GCMovement.class); var facing=mockStatic(MovementCommands.class)) {
            attacks.when(()->BotAttack.resolveEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            config.when(()->BotAttackConfig.resolve(Job.HERMIT,WeaponType.CLAW)).thenReturn(new BotAttackConfig.JobAttacks(profile,null,null));
            skills.when(()->SkillFactory.getSkill(Hermit.AVENGER)).thenReturn(skill);
            data.when(()->BotAttackData.projectileFor(WeaponType.CLAW,bot)).thenReturn(2070000);
            var method=BotFlavor.class.getDeclaredMethod("doSkillSwing",Character.class); method.setAccessible(true); method.invoke(null,bot);
            var packet=ArgumentCaptor.forClass(Packet.class); verify(map).broadcastMessage(eq(bot),packet.capture(),eq(false));
            var bytes=ByteBuffer.wrap(packet.getValue().getBytes()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(SendOpcode.RANGED_ATTACK.getValue(),Short.toUnsignedInt(bytes.getShort()));
            assertEquals(22111,bytes.getInt()); assertEquals(0,bytes.get()); assertEquals(0x5b,bytes.get());
            assertEquals(30,bytes.get()); assertEquals(Hermit.AVENGER,bytes.getInt());
            assertEquals(0,bytes.get()); assertEquals(56,bytes.get()); assertEquals(0,bytes.get());
            assertEquals(4,bytes.get()); assertEquals(10,bytes.get()); assertEquals(2070000,bytes.getInt());
            assertEquals(0,bytes.getInt()); assertFalse(bytes.hasRemaining());
            attacks.verify(()->BotAttack.skillSwing(bot,Hermit.AVENGER),never());
        }
    }
}

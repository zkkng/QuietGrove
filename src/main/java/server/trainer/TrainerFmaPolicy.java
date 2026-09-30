package server.trainer;

import client.Character;
import constants.skills.*;
import net.packet.Packet;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import tools.PacketCreator;
import java.util.Map;
import java.util.Set;

/** Extra targets retain the actual cast's packet family, projectile, charge and line count. */
final class TrainerFmaPolicy {
    private static final Set<Integer> SPECIAL = Set.of(ChiefBandit.MESO_EXPLOSION, Cleric.HEAL,
            Paladin.HEAVENS_HAMMER, Aran.COMBO_TEMPEST, Aran.BODY_PRESSURE,
            Marauder.ENERGY_CHARGE, ThunderBreaker.ENERGY_CHARGE,
            NightWalker.POISON_BOMB, DragonKnight.SACRIFICE);
    static boolean supported(int skill) {
        return !SPECIAL.contains(skill) && skill % 10000000 != 1009 && skill % 10000000 != 1020;
    }
    static Packet packet(Character actor, AttackInfo a, Map<Integer,AttackTarget> targets) {
        int lines=targets.values().iterator().next().damageLines().size();
        if(targets.size()>15 || lines<1 || lines>15 || targets.values().stream().anyMatch(t->t.damageLines().size()!=lines))
            throw new IllegalArgumentException("Invalid FMA packet batch");
        int packed=(targets.size()<<4)|lines;
        if(a.magic) {
            int charge=Set.of(Evan.FIRE_BREATH,Evan.ICE_BREATH,FPArchMage.BIG_BANG,ILArchMage.BIG_BANG,Bishop.BIG_BANG).contains(a.skill)?a.charge:-1;
            return PacketCreator.magicAttack(actor,a.skill,a.skilllevel,a.stance,packed,targets,charge,a.speed,a.direction,a.display);
        }
        if(a.ranged) {
            int stance=Set.of(Bowmaster.HURRICANE,Marksman.PIERCING_ARROW,Corsair.RAPID_FIRE,WindArcher.HURRICANE).contains(a.skill)?a.rangedirection:a.stance;
            return PacketCreator.rangedAttack(actor,a.skill,a.skilllevel,stance,packed,a.trainerProjectile,targets,a.speed,a.direction,a.display);
        }
        return PacketCreator.closeRangeAttack(actor,a.skill,a.skilllevel,a.stance,packed,targets,a.speed,a.direction,a.display);
    }
}

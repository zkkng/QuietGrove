package soloMapling.ArtificialPlayer.HybridPilot;

import client.Character;
import client.status.MonsterStatus;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionCombat;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionIncomingDamage;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotMobHitboxProvider;
import tools.PacketCreator;

import java.awt.Rectangle;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Synchronous, character-explicit effects. No movement driver, delayed damage, drops or synthetic trade helpers. */
final class HybridPilotEffects implements HybridPilotBot.Effects {
    static boolean targetAllowed(Monster target, MapleMap map) {
        return target.getMap() == map && target.isAlive() && !target.isFake() && !target.isBoss()
                && !target.isEncounterMarker() && target.getIncidentOwner() == null && !target.getStats().isFriendly();
    }

    private static Monster nearest(Character bot, MapleMap map, Rectangle range) {
        return map.getAllMonsters().stream().filter(m -> targetAllowed(m, map))
                .filter(m -> {
                    Rectangle body = BotMobHitboxProvider.getMobBounds(m);
                    return body != null && body.intersects(range);
                }).min(Comparator.comparingDouble(m -> m.getPosition().distanceSq(bot.getPosition()))).orElse(null);
    }

    @Override public boolean contact(Character bot, MapleMap map) {
        Monster target = nearest(bot, map, new Rectangle(bot.getPosition().x - 12, bot.getPosition().y - 45, 24, 45));
        if (target == null || !HybridPilotBot.usable(bot, map)) return false;
        int damage = CompanionIncomingDamage.physical(bot, target);
        bot.addHP(-Math.min(bot.getHp(), damage));
        map.broadcastMessage(PacketCreator.damagePlayer(-1, target.getId(), bot.getId(), damage,
                0, 0, false, 0, false, 0, 0, 0));
        return true;
    }

    @Override public boolean attack(Character bot, MapleMap map) {
        Monster target = nearest(bot, map, new Rectangle(bot.getPosition().x - 90, bot.getPosition().y - 55, 180, 65));
        if (target == null || !HybridPilotBot.usable(bot, map) || !targetAllowed(target, map)) return false;
        // First canary only uses the ordinary basic melee packet: one target, one damage line, no skill cost.
        if (target.isBuffed(MonsterStatus.WEAPON_REFLECT) || target.isBuffed(MonsterStatus.WEAPON_IMMUNITY)
                || target.isBuffed(MonsterStatus.HARD_SKIN)) return false;
        var weapon = BotAttack.resolveEquippedWeaponType(bot);
        if (weapon == null || !switch (weapon) {
            case SWORD1H, SWORD2H, GENERAL1H_SWING, GENERAL1H_STAB, GENERAL2H_SWING, GENERAL2H_STAB,
                    SPEAR_STAB, SPEAR_SWING, POLE_ARM_SWING, POLE_ARM_STAB -> true;
            default -> false;
        }) return false;
        double base = bot.calculateMaxBaseDamage(bot.getTotalWatk());
        int defense = target.effectiveStat(target.getStats().getPDDamage(), MonsterStatus.WEAPON_DEFENSE_UP, MonsterStatus.WDEF);
        var random = ThreadLocalRandom.current();
        int damage = random.nextDouble() >= CompanionCombat.hitChance(bot, target, false) ? 0
                : (int) Math.min(999999, Math.max(1, base * random.nextDouble(.6, 1) - defense * .5));
        int facing = target.getPosition().x < bot.getPosition().x ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        var packet = PacketCreator.closeRangeAttack(bot, 0, 0, facing, 0x11,
                Map.of(target.getObjectId(), new AttackTarget((short) 0, List.of(damage))),
                BotAttackData.DEFAULT_ATTACK_SPEED, BotAttackData.actionFor(0, weapon), 0);
        if (!HybridPilotBot.usable(bot, map) || !targetAllowed(target, map)) return false;
        map.broadcastMessage(bot, packet, false);
        map.damageMonster(bot, target, damage); // canonical death/EXP/drop rules; no second synthetic loot roll
        return true;
    }

    @Override public void speak(Character bot, String message) { SocialCommands.BotFullChat(bot, message); }
    @Override public void remove(Character bot) { BotGeneration.removeBotFromServer(bot); }
}

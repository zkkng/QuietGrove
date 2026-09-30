package soloMapling.ArtificialPlayer.CompanionSystem;

import client.BuffStat;
import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import server.life.Monster;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Shared real-time invulnerability window for clientless contact/projectile/map damage. */
public final class CompanionIncomingDamage {
    private static final Map<Character, Long> until = new WeakHashMap<>();
    private static final class Environment { long nextCheck, nextField; }
    private static final Map<Character,Environment> environments = new WeakHashMap<>();
    private CompanionIncomingDamage() {}
    public static int physical(Character bot, Monster monster) {
        return physical(bot,monster,monster.getPADamage());
    }
    public static int physical(Character bot, Monster monster, int power) {
        power = monster.effectiveStat(power,client.status.MonsterStatus.WEAPON_ATTACK_UP,client.status.MonsterStatus.WATK);
        double avoidance = bot.getTotalDex() * .25 + bot.getTotalLuk() * .5;
        double miss = Math.min(.75, avoidance / Math.max(100, monster.getLevel() * 8.0));
        if (ThreadLocalRandom.current().nextDouble() < miss) return 0;
        int defense = 0;
        for (var item : bot.getInventory(InventoryType.EQUIPPED).list()) if (item instanceof Equip equip) defense += equip.getWdef();
        Integer buff = bot.getBuffedValue(BuffStat.WDEF);
        if (buff != null) defense += buff;
        return Math.max(1, (int)(power * ThreadLocalRandom.current().nextDouble(.8, 1.2) - defense * .5));
    }
    /** -1 means immune to this duplicate arrival; 0 is a genuine MISS that still starts i-frames. */
    public static synchronized int apply(Character bot, int requested, boolean fall) {
        return applyAttack(bot, requested, fall, false, 0);
    }
    public static synchronized int applyAttack(Character bot, int requested, boolean fall, boolean deadly, int mpBurn) {
        if (!CompanionRuntime.active(bot) || !bot.isAlive()) return -1;
        long now = System.currentTimeMillis();
        if (now < until.getOrDefault(bot, 0L)) return -1;
        until.put(bot, now + 1500);
        int damage = deadly ? Math.max(0, bot.getHp() - 1) : Math.max(0, requested);
        Integer magicGuard = bot.getBuffedValue(BuffStat.MAGIC_GUARD);
        if (!fall && !deadly && magicGuard != null && damage > 0) {
            int diverted = Math.min(bot.getMp(), damage * magicGuard / 100);
            bot.addMP(-diverted); damage -= diverted;
        }
        if (fall) damage = Math.min(damage, Math.max(0, bot.getHp() - 1));
        if (deadly) bot.addMP(-Math.max(0, bot.getMp() - 1));
        else if (mpBurn > 0) bot.addMP(-Math.min(bot.getMp(), mpBurn));
        bot.addHP(-damage);
        BossCombatEvidence.incoming(bot,damage);
        return damage;
    }
    public static void environment(Character bot) {
        if (bot == null || bot.getMap() == null || !CompanionRuntime.active(bot) || !bot.isAlive()) return;
        long now = System.currentTimeMillis(); boolean field;
        synchronized (environments) {
            var state = environments.computeIfAbsent(bot,c -> new Environment());
            if (now < state.nextCheck) return;
            state.nextCheck = now+1000;
            long interval = Math.max(1000L,(long)config.YamlConfig.config.server.MAP_DAMAGE_OVERTIME_INTERVAL
                    * config.YamlConfig.config.server.MAP_DAMAGE_OVERTIME_COUNT);
            if (state.nextField == 0) state.nextField = now+interval;
            field = now >= state.nextField;
            if (field) state.nextField = now+interval;
        }
        bot.expireClientlessEffects();
        var poison = bot.getAllDiseases().get(client.Disease.POISON);
        if (poison != null && poison.left > 0) bot.addHP(-Math.min(Math.max(0,bot.getHp()-1),Math.max(0,poison.right.getX())));
        for (var object : bot.getMap().getMapObjectsInRange(bot.getPosition(),Double.POSITIVE_INFINITY,List.of(server.maps.MapObjectType.MIST))) {
            var mist = (server.maps.Mist)object;
            if (!mist.isMobMist() || !mist.getBox().contains(bot.getPosition()) || !mist.makeChanceResult()) continue;
            int damage = Math.min(Math.max(0,bot.getHp()-1),Math.max(0,mist.getMobSkill().getX()));
            if (damage > 0) {
                bot.addHP(-damage); BossCombatEvidence.incoming(bot,damage);
                bot.getMap().broadcastMessage(tools.PacketCreator.damagePlayer(-4,0,bot.getId(),damage,0,0,false,0,false,0,0,0));
            }
        }
        // The world's ordinary HP-decrease schedule handles actors entered into the world already.
        if (field && bot.isAwayFromWorld() && bot.getMap().getHPDec() > 0) bot.doHurtHp();
    }
    public static synchronized void clear(Character bot) {
        until.remove(bot); synchronized (environments) { environments.remove(bot); }
    }
}

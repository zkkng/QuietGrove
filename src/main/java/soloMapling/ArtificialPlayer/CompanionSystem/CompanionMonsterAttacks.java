package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import server.TimerManager;
import server.life.*;
import tools.PacketCreator;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Only server-accepted monster actions reach this adapter; no invented offscreen casts. */
public final class CompanionMonsterAttacks {
    private record DamageToken(Object map, long task, long event, long exposure) {}
    private static DamageToken damageToken(Character c) {
        var task = CompanionTaskService.shared().task(c.getId()).orElse(null);
        var event = CompanionTaskService.shared().eventLease(c.getId()).orElse(null);
        return new DamageToken(c.getMap(),task == null ? 0 : task.generation(),
                event != null && event.committed() && server.events.gm.EventBotRuntime.physical(c) ? event.generation() : 0,
                server.events.gm.IncidentService.exposureGeneration(c));
    }
    private static boolean validDamage(Character c, DamageToken token) {
        if (c == null || token == null || !c.isAlive() || c.getMap() != token.map()) return false;
        var current = damageToken(c);
        return token.task()>0 && token.task()==current.task() || token.event()>0 && token.event()==current.event()
                || token.exposure()>0 && token.exposure()==current.exposure();
    }
    private static final Map<Monster, Map<Integer, Long>> lastActions = new WeakHashMap<>();
    private CompanionMonsterAttacks() {}
    public static void accepted(Monster monster, int attackIndex, boolean left) {
        var map = monster.getMap();
        if (map == null || map.getCharacters().stream().noneMatch(CompanionRuntime::active)) return;
        MobAttackInfo info = MobAttackInfoFactory.getMobAttackInfo(monster, attackIndex);
        if (info == null) return;
        Point origin = new Point(monster.getPosition());
        Map<Integer, DamageToken> generations = new HashMap<>();
        for (Character bot : map.getCharacters()) {
            if (!soloMapling.ArtificialPlayer.BotHelpers.isBot(bot)) continue;
            DamageToken token = damageToken(bot);
            if (token.task()>0 || token.event()>0 || token.exposure()>0) generations.put(bot.getId(),token);
        }
        Rectangle area = info.attackBox(origin, left);
        // Unknown projectile geometry must not silently become a map-wide attack.
        if (area == null && !info.isProjectile()) return;
        long now = System.currentTimeMillis();
        synchronized (lastActions) {
            var actions = lastActions.computeIfAbsent(monster, ignored -> new HashMap<>());
            if (now - actions.getOrDefault(attackIndex, 0L) < Math.max(500, info.getAttackDelay())) return;
            actions.put(attackIndex, now);
        }
        if (info.isProjectile()) {
            Point start = info.projectileStart(origin,left);
            for (Character bot : map.getCharacters().stream().filter(c -> c.isAlive() && info.canTarget(origin,c.getPosition(),left))
                    .sorted(Comparator.comparingDouble(c -> c.getPosition().distanceSq(start))).limit(info.getTargetCount()).toList()) {
                DamageToken generation = generations.get(bot.getId()); if (generation == null) continue;
                Point aim = new Point(bot.getPosition());
                TimerManager.getInstance().schedule(() -> {
                    if (monster.getMap() == map && monster.isAlive() && map.getMonsterByOid(monster.getObjectId()) == monster
                            && bot.getMap() == map && bot.getPosition().distanceSq(aim) <= 40*40)
                        apply(bot,monster,info,attackIndex,left,generation);
                },info.getAttackDelay()+info.projectileDelay(start,aim));
            }
            return;
        }
        TimerManager.getInstance().schedule(() -> {
            if (monster.getMap() != map || !monster.isAlive() || map.getMonsterByOid(monster.getObjectId()) != monster) return;
            for (Character bot : map.getPlayersInRange(area)) {
                DamageToken generation = generations.get(bot.getId());
                if (generation != null) apply(bot,monster,info,attackIndex,left,generation);
            }
        }, info.getAttackDelay());
    }
    private static void apply(Character bot, Monster monster, MobAttackInfo info, int attackIndex, boolean left, DamageToken expected) {
        if (!validDamage(bot,expected)) return;
        int requested = info.isMagic() ? magicDamage(bot,monster,info.getAttackPower())
                : CompanionIncomingDamage.physical(bot,monster,info.getAttackPower());
        int damage = CompanionIncomingDamage.applyAttack(bot,requested,false,info.isDeadlyAttack(),info.getMpBurn());
        if (damage < 0) return;
        monster.getMap().broadcastMessage(bot,PacketCreator.damagePlayer(attackIndex,monster.getId(),bot.getId(),damage,0,
                left ? 1 : 0,false,0,false,0,0,0),false);
        if (damage > 0 && info.getDiseaseSkill() > 0) MobSkillType.from(info.getDiseaseSkill()).ifPresent(type ->
                MobSkillFactory.getMobSkillOrThrow(type,info.getDiseaseLevel()).applyEffect(bot,monster,false,new ArrayList<>()));
    }
    public static long generation(Character c) {
        var task = CompanionTaskService.shared().task(c.getId()).orElse(null);
        if (task != null) return task.generation();
        var event = CompanionTaskService.shared().eventLease(c.getId()).orElse(null);
        return event != null && event.committed() && (BossMonsterController.incidentActor(c)
                || server.events.gm.GmEventService.getInstance().realPresence(c)) ? event.generation() : 0;
    }
    public static java.util.function.Predicate<Character> actorFence(Monster monster) {
        var map = monster.getMap(); Map<Integer,DamageToken> generations = new HashMap<>();
        for (Character c : map.getCharacters()) if (soloMapling.ArtificialPlayer.BotHelpers.isBot(c)) generations.put(c.getId(),damageToken(c));
        return c -> c != null && c.isAlive() && c.getMap() == map && (!soloMapling.ArtificialPlayer.BotHelpers.isBot(c)
                || validDamage(c,generations.get(c.getId())));
    }
    private static int magicDamage(Character bot, Monster monster, int power) {
        power = monster.effectiveStat(power,client.status.MonsterStatus.MAGIC_ATTACK_UP,client.status.MonsterStatus.MATK);
        int defense = bot.getTotalInt();
        for (var item : bot.getInventory(InventoryType.EQUIPPED).list()) if (item instanceof Equip equip) defense += equip.getMdef();
        Integer buff = bot.getBuffedValue(client.BuffStat.MDEF);
        if (buff != null) defense += buff;
        return Math.max(1, (int)(power * ThreadLocalRandom.current().nextDouble(.8, 1.2) - defense * .5));
    }
}

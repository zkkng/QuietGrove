package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.SkillFactory;
import client.inventory.*;
import server.life.LifeFactory;
import java.util.*;

/** Admission predicts risk from the materialized build; no level/job cosmetics or generated elites. */
public final class BossSuitability {
    public record BuildProfile(int version, int level, int str, int dex, int intelligence, int luk,
            int hp, int mp, int weaponId, Map<Integer,Integer> learnedSkills, int hpPotions, int mpPotions,
            BossIntentParser.Role role, List<Integer> attackRotation, Map<Integer,Map<String,Integer>> equipmentConstraints,
            String levelingPolicy) {}
    public record Assessment(boolean suitable, String reason, double hitChance, double survivalMargin,
                             BuildProfile build) {
        public static Assessment refuse(String why) { return new Assessment(false, why, 0, 0, null); }
    }
    private BossSuitability() {}
    static double rank(int level,int bossLevel,double hit,double distanceSquared) {
        return (1-hit)*10000+Math.abs(level-bossLevel)*100+Math.min(1000,Math.sqrt(distanceSquared));
    }
    public static BuildProfile profile(Character bot) {
        CompanionBuild.prepare(bot);
        Map<Integer,Integer> skills = new TreeMap<>();
        bot.getSkills().keySet().forEach(s -> skills.put(s.getId(),(int)bot.getSkillLevel(s)));
        Item weapon = bot.getInventory(InventoryType.EQUIPPED).getItem((short)-11);
        Map<Integer,Map<String,Integer>> constraints = new TreeMap<>();
        for (Item item : bot.getInventory(InventoryType.EQUIPPED).list()) {
            var req = server.ItemInformationProvider.getInstance().getEquipStats(item.getItemId());
            if (req != null) constraints.put(item.getItemId(),Map.copyOf(req));
        }
        Set<Integer> rotation = new LinkedHashSet<>();
        var weaponType = soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack.resolveEquippedWeaponType(bot);
        for (client.Job job : client.Job.values()) if (job.getId() >= 100 && job.getId() < 600 && bot.getJob().isA(job)) {
            var kit = CompanionBuild.attackKit(job,weaponType);
            for (var attack : new soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile[]{kit.single(),kit.aoe()})
                if (attack != null && bot.getSkillLevel(attack.skillFor(weaponType)) > 0) rotation.add(attack.skillFor(weaponType));
        }
        if (rotation.isEmpty()) rotation.add(0);
        int hpPots = 0, mpPots = 0;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (item.getItemId() / 10000 != 200) continue;
            var e = server.ItemInformationProvider.getInstance().getItemEffect(item.getItemId());
            if (e == null) continue;
            if (e.getHp() > 0 || e.getHpRate() > 0) hpPots += item.getQuantity();
            if (e.getMp() > 0 || e.getMpRate() > 0) mpPots += item.getQuantity();
        }
        BossIntentParser.Role role = bot.getSkillLevel(constants.skills.Cleric.HEAL) > 0 ? BossIntentParser.Role.SUPPORT
                : bot.getJob().getJobNiche() == 2 ? BossIntentParser.Role.MAGIC
                : bot.getJob().getJobNiche() == 1 || weaponType == client.inventory.WeaponType.KNUCKLE ? BossIntentParser.Role.FRONTLINE : BossIntentParser.Role.RANGED;
        return new BuildProfile(2, bot.getLevel(), bot.getStr(), bot.getDex(), bot.getInt(), bot.getLuk(),
                bot.getCurrentMaxHp(), bot.getCurrentMaxMp(), weapon == null ? 0 : weapon.getItemId(), Map.copyOf(skills), hpPots, mpPots, role,
                List.copyOf(rotation),Map.copyOf(constraints),"Level-budgeted AP/SP and growth; finite starter supplies once per character lifetime");
    }
    public static Assessment assess(Character bot, BossDefinition boss, BossIntentParser.Role requested) {
        if (bot == null || !bot.isAlive() || !CompanionBuild.supported(bot)) return Assessment.refuse("no usable equipped build");
        BuildProfile build = profile(bot);
        String constraints = CompanionBuild.constraints(bot);
        if (!constraints.isEmpty()) return Assessment.refuse(constraints);
        if (bot.getLevel() < Math.max(10, boss.minimumLevel() - 10)) return Assessment.refuse("too inexperienced for this boss");
        if (requested != BossIntentParser.Role.ANY && requested != build.role()) return Assessment.refuse("requested role unavailable");
        double hit = 1, survival = Double.POSITIVE_INFINITY;
        for (int id : boss.phases()) {
            if (!boss.attackTemplate(id)) continue;
            var monster = LifeFactory.getMonster(id);
            if (monster == null) return Assessment.refuse("missing boss stats");
            hit = Math.min(hit, CompanionCombat.hitChance(bot, monster, build.role() == BossIntentParser.Role.MAGIC || build.role() == BossIntentParser.Role.SUPPORT));
            int threat = Math.max(monster.getStats().getPADamage(), monster.getStats().getMADamage());
            for (int attack = 0; attack < 9; attack++) {
                var info = server.life.MobAttackInfoFactory.getMobAttackInfo(monster,attack);
                if (info != null && !info.isDeadlyAttack()) threat = Math.max(threat,info.getAttackPower());
            }
            double pool = build.hp();
            int mgLevel = bot.getSkillLevel(constants.skills.Magician.MAGIC_GUARD);
            if (mgLevel > 0) {
                var mg = SkillFactory.getSkill(constants.skills.Magician.MAGIC_GUARD).getEffect(mgLevel);
                pool += Math.min(bot.getMp(), build.hp() * mg.getX() / (double)Math.max(1,100-mg.getX()));
            }
            survival = Math.min(survival, pool / Math.max(1, threat * 1.2));
        }
        if (hit < .35) return new Assessment(false,"I can't reliably hit that boss",hit,survival,build);
        if (survival < 1.2) return new Assessment(false,"I can't survive that boss's attacks",hit,survival,build);
        if (build.hpPotions() < 5 || build.mpPotions() < 5 || bot.getHp() < build.hp() * .5 || bot.getMp() < build.mp() * .3)
            return new Assessment(false,"I need real supplies and rest first",hit,survival,build);
        var cleanses=new HashSet<>(boss.threats().disablingCleanses());
        if(build.role()==BossIntentParser.Role.MAGIC || build.role()==BossIntentParser.Role.SUPPORT) cleanses.remove(client.Disease.DARKNESS);
        String cleanseFailure=BossThreats.cleanseFailure(bot,cleanses);
        if(!cleanseFailure.isEmpty()) return new Assessment(false,cleanseFailure,hit,survival,build);
        String access = BossAccess.actorFailure(bot,boss);
        return new Assessment(access.isEmpty(), access.isEmpty() ? "I'll help as " + build.role().name().toLowerCase(Locale.ROOT)
                + (hit < .7 || survival < 1.7 ? "; this looks risky." : "; I can handle this.") : access,hit,survival,build);
    }
}

package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.skills.Cleric;
import constants.skills.Priest;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import java.util.*;

/** NPC build materialization: level-budgeted AP/SP and one finite starter pack per character lifetime. */
public final class CompanionBuild {
    private static final Map<Character, Integer> initialized = Collections.synchronizedMap(new WeakHashMap<>());
    private CompanionBuild() {}
    /** Ordinary supported actor startup; no incident or recruitment creates its supply inventory. */
    public static void initializeAmbient(Character bot) {
        if (supported(bot)) prepare(bot);
    }
    static int apBudget(Character bot) {
        int tier = bot.getJob().getJobTier();
        return 20 + 5 * Math.max(0,bot.getLevel()-1) + 5 * Math.max(0,tier-2);
    }
    static int skillBudget(int skillJob, int level) {
        int start = skillJob % 100 == 0 ? (skillJob == 200 ? 8 : 10) : skillJob % 10 == 0 ? 30 : skillJob % 10 == 1 ? 70 : 120;
        int end = start < 30 ? 29 : start == 30 ? 69 : start == 70 ? 119 : 200;
        return Math.max(0,Math.min(level,end)-start)*3 + (level >= start ? start == 120 ? 3 : 1 : 0);
    }
    /** Reject preexisting inflated or incompatible builds instead of silently granting boss capability. */
    static String constraints(Character bot) {
        if ((long)bot.getStr()+bot.getDex()+bot.getInt()+bot.getLuk()+bot.getRemainingAp() > apBudget(bot)) return "stats exceed the level AP budget";
        int budget = 0, spent = 0;
        for (Job job : Job.values()) if (job.getId() >= 100 && job.getId() < 600 && bot.getJob().isA(job))
            budget += skillBudget(job.getId(),bot.getLevel());
        for (Skill skill : bot.getSkills().keySet()) {
            int level = bot.getSkillLevel(skill), jobId = skill.getId()/10000;
            if (level == 0 || jobId == 0) continue;
            Job job = Job.getById(jobId);
            if (job == null || !bot.getJob().isA(job) || level < 0 || level > skill.getMaxLevel()) return "learned skill is incompatible with this build";
            spent += level;
            for (var prerequisite : skill.getPrerequisites().entrySet())
                if (bot.getSkillLevel(prerequisite.getKey()) < prerequisite.getValue()) return "learned skill prerequisites are missing";
        }
        if (spent > budget) return "skills exceed the level SP budget";
        var ii = server.ItemInformationProvider.getInstance();
        for (Item item : bot.getInventory(InventoryType.EQUIPPED).list()) {
            var req = ii.getEquipStats(item.getItemId());
            if (req == null) return "equipment data is missing";
            int jobs = req.getOrDefault("reqJob",0), niche = bot.getJob().getJobNiche();
            if (req.getOrDefault("reqLevel",0) > bot.getLevel() || jobs < 0 || jobs > 0 && (jobs & (1 << (niche-1))) == 0
                    || req.getOrDefault("reqSTR",0) > bot.getTotalStr() || req.getOrDefault("reqDEX",0) > bot.getTotalDex()
                    || req.getOrDefault("reqINT",0) > bot.getTotalInt() || req.getOrDefault("reqLUK",0) > bot.getTotalLuk()
                    || req.getOrDefault("reqPOP",0) > bot.getFame()) return "equipped gear requirements are not met";
        }
        return "";
    }
    /** Admission uses a real equipped weapon and WZ-backed learned-skill plan, never the job label alone. */
    public static boolean supported(Character bot) {
        int job = bot.getJob().getId();
        if (job < 100 || job >= 600 || bot.getLevel() < 10 || bot.getCurrentMaxHp() <= 0
                || bot.getInventory(InventoryType.EQUIPPED).getItem((short)-11) == null) return false;
        var weapon = BotAttack.resolveEquippedWeaponType(bot);
        if (weapon == null || weapon == client.inventory.WeaponType.NOT_A_WEAPON) return false;
        if (bot.getJob().getJobNiche() != 2) return switch(bot.getJob().getJobNiche()) {
            case 1 -> switch(weapon) {case SWORD1H,SWORD2H,GENERAL1H_SWING,GENERAL1H_STAB,GENERAL2H_SWING,GENERAL2H_STAB,POLE_ARM_SWING,POLE_ARM_STAB,SPEAR_STAB,SPEAR_SWING -> true;default -> false;};
            case 3 -> weapon == client.inventory.WeaponType.BOW || weapon == client.inventory.WeaponType.CROSSBOW;
            case 4 -> weapon == client.inventory.WeaponType.CLAW || weapon == client.inventory.WeaponType.DAGGER_THIEVES || weapon == client.inventory.WeaponType.DAGGER_OTHER;
            case 5 -> weapon == client.inventory.WeaponType.GUN || weapon == client.inventory.WeaponType.KNUCKLE;
            default -> false;
        };
        if (weapon != client.inventory.WeaponType.STAFF && weapon != client.inventory.WeaponType.WAND) return false;
        Skill bolt = SkillFactory.getSkill(constants.skills.Magician.MAGIC_CLAW);
        return bolt != null && bolt.getMaxLevel() > 0;
    }
    static BotAttackConfig.JobAttacks attackKit(Job job, client.inventory.WeaponType weapon) {
        if (!job.isA(Job.PIRATE)) return BotAttackConfig.resolve(job,weapon);
        if (weapon == client.inventory.WeaponType.GUN) return new BotAttackConfig.JobAttacks(
                BotAttackProfile.ranged(constants.skills.Pirate.DOUBLE_SHOT,2),
                job.isA(Job.GUNSLINGER) ? BotAttackProfile.rangedAoe(constants.skills.Gunslinger.INVISIBLE_SHOT,1) : null,null);
        return new BotAttackConfig.JobAttacks(job.isA(Job.BRAWLER) ? BotAttackProfile.melee(constants.skills.Brawler.DOUBLE_UPPERCUT,2)
                : BotAttackProfile.melee(constants.skills.Pirate.FLASH_FIST,1),BotAttackProfile.meleeAoe(constants.skills.Pirate.SOMERSAULT_KICK,1),null);
    }
    public static void prepare(Character bot) {
        synchronized (initialized) {
            int level = bot.getLevel();
            Integer previousLevel = initialized.get(bot);
            if (previousLevel != null && previousLevel == level) return;
            int sum = bot.getStr() + bot.getDex() + bot.getInt() + bot.getLuk();
            int available = Math.max(0, apBudget(bot) - sum);
            bot.changeRemainingAp(available, true);
            int secondary = Math.min(available, Math.max(0, level + 4 - switch (bot.getJob().getJobNiche()) {
                case 2 -> bot.getLuk(); case 3 -> bot.getStr(); default -> bot.getDex();
            }));
            switch (bot.getJob().getJobNiche()) {
                case 2 -> { bot.assignLuk(secondary); bot.assignInt(available - secondary); }
                case 3 -> { bot.assignStr(secondary); bot.assignDex(available - secondary); }
                case 4 -> { bot.assignDex(secondary); bot.assignLuk(available - secondary); }
                default -> { bot.assignDex(secondary); bot.assignStr(available - secondary); }
            }
            // Missing NPC growth is initialized once, never on each invite or potion exhaustion.
            boolean mage = bot.getJob().getJobNiche() == 2;
            int expectedHp = 50 + level * (mage ? 12 : bot.getJob().getJobNiche() == 1 ? 45 : 24);
            int expectedMp = 30 + level * (mage ? 45 : 12);
            double hpRatio = bot.getHp() / (double) Math.max(1, bot.getCurrentMaxHp());
            double mpRatio = bot.getMp() / (double) Math.max(1, bot.getCurrentMaxMp());
            // Current HP/MP setters clamp against recalculated local maxima. Install the new
            // maximum first, then preserve the observed fraction against that actual maximum.
            if (bot.getMaxHp() < expectedHp) {
                bot.updateMaxHp(expectedHp);
                bot.updateHp((int) (bot.getCurrentMaxHp() * hpRatio));
            }
            if (bot.getMaxMp() < expectedMp) {
                bot.updateMaxMp(expectedMp);
                bot.updateMp((int) (bot.getCurrentMaxMp() * mpRatio));
            }
            allocateSkills(bot);
            // Starter supplies are finite inventory items, not a repeating resource regeneration policy.
            var use = bot.getInventory(InventoryType.USE);
            if (previousLevel == null && use.countById(2000002) == 0) use.addItem(new Item(2000002, (short) 0, (short) 50));
            if (previousLevel == null && use.countById(2000006) == 0) use.addItem(new Item(2000006, (short) 0, (short) 50));
            int ammo = switch (BotAttack.resolveEquippedWeaponType(bot)) {
                case BOW -> 2060000; case CROSSBOW -> 2061000; case CLAW -> 2070000; case GUN -> 2330000; default -> 0;
            };
            if (previousLevel == null && ammo != 0 && use.countById(ammo) == 0) use.addItem(new Item(ammo, (short)0, (short)1000));
            initialized.put(bot, level);
        }
    }
    private static void allocateSkills(Character bot) {
        var weapon = BotAttack.resolveEquippedWeaponType(bot);
        LinkedHashSet<Integer> priority = new LinkedHashSet<>();
        if (bot.getJob().isA(Job.BOWMAN)) priority.add(constants.skills.Archer.CRITICAL_SHOT);
        if (bot.getJob().isA(Job.ASSASSIN)) priority.add(constants.skills.Assassin.CRITICAL_THROW);
        if (bot.getJob().isA(Job.PRIEST)) { priority.add(Priest.HOLY_SYMBOL); priority.add(Priest.DISPEL); }
        if (bot.getJob().isA(Job.CLERIC)) priority.add(Cleric.HEAL);
        int movement = CompanionCombat.movementSkill(bot, false);
        if (movement > 0) priority.add(movement);
        if (bot.getJob().isA(Job.HERMIT)) priority.add(constants.skills.Hermit.FLASH_JUMP);
        for (Job ancestor : Job.values()) {
            if (ancestor.getId() <= 0 || ancestor.getId() >= 1000 || !bot.getJob().isA(ancestor)) continue;
            var attacks = attackKit(ancestor, weapon);
            for (BotAttackProfile profile : new BotAttackProfile[]{attacks.single(), attacks.aoe()})
                if (profile != null && profile.skillFor(weapon) > 0) priority.add(profile.skillFor(weapon));
        }
        priority.addAll(BotBuffConfig.buffsForJob(bot.getJob()));
        for (int id : priority) {
            Skill skill = SkillFactory.getSkill(id);
            if (skill != null) learn(bot, skill, Math.min(skill.getMaxLevel(), skill.isFourthJob() ? 10 : 30), new HashSet<>());
        }
    }
    private static boolean learn(Character bot, Skill skill, int desired, Set<Integer> stack) {
        if (!stack.add(skill.getId())) return false;
        for (var prerequisite : skill.getPrerequisites().entrySet()) {
            Skill required = SkillFactory.getSkill(prerequisite.getKey());
            if (required == null || !learn(bot, required, prerequisite.getValue(), stack)) return false;
        }
        stack.remove(skill.getId());
        int skillJob = skill.getId() / 10000;
        Job job = Job.getById(skillJob);
        if (job == null || !bot.getJob().isA(job)) return false;
        int budget = skillBudget(skillJob,bot.getLevel());
        int spent = bot.getSkills().keySet().stream().filter(s -> s.getId() / 10000 == skillJob).mapToInt(bot::getSkillLevel).sum();
        int current = bot.getSkillLevel(skill);
        int next = Math.min(desired, current + Math.max(0, budget - spent));
        if (next > current) bot.changeSkillLevel(skill, (byte) next, skill.isFourthJob() ? 10 : 0, -1);
        return bot.getSkillLevel(skill) >= desired;
    }
}

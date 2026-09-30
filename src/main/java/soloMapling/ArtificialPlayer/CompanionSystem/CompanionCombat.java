package soloMapling.ArtificialPlayer.CompanionSystem;

import client.*;
import client.Character;
import client.inventory.*;
import constants.skills.Cleric;
import server.ItemInformationProvider;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapObjectType;
import soloMapling.ArtificialPlayer.BotAttackSystem.*;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import tools.PacketCreator;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Character-stat combat for recruited bots, separate from decorative ambient tier damage. */
public final class CompanionCombat {
    public static int movementSkill(Character bot, boolean flashJump) {
        if (flashJump) return bot.getJob().isA(Job.HERMIT) ? constants.skills.Hermit.FLASH_JUMP : 0;
        if (bot.getJob().isA(Job.CLERIC)) return constants.skills.Cleric.TELEPORT;
        if (bot.getJob().isA(Job.FP_WIZARD)) return constants.skills.FPWizard.TELEPORT;
        if (bot.getJob().isA(Job.IL_WIZARD)) return constants.skills.ILWizard.TELEPORT;
        return 0;
    }
    public static int movementRange(Character bot) {
        StatEffect effect = effect(bot, movementSkill(bot, false));
        return effect == null ? 0 : Math.max(0, effect.getX());
    }
    public static boolean spendMovement(Character bot, boolean flashJump) {
        StatEffect effect = effect(bot, movementSkill(bot, flashJump));
        return effect != null && skillAllowed(bot,movementSkill(bot,flashJump))
                && !server.maps.FieldLimit.MOVEMENTSKILLS.check(bot.getMap().getFieldLimit()) && bot.isAlive() && !bot.hasDisease(Disease.SEAL)
                && !bot.hasDisease(Disease.STUN) && !bot.hasDisease(Disease.SEDUCE) && pay(bot, effect);
    }
    // Separate from canonical Party and shared lease locks: buff registration may publish party HP.
    private static final Object BUFF_CAST_LOCK = new Object();
    private long nextAction, nextPotion, lastResourceWarning;
    private final Map<Integer, Long> skillCooldown = new HashMap<>();
    public boolean tickIncident(Character bot) {
        return BossMonsterController.incidentActor(bot) && tick(bot,bot);
    }
    public boolean tick(Character bot, Character leader) {
        var lease = CompanionTaskService.shared().task(bot.getId()).orElse(null);
        long generation = lease == null ? CompanionMonsterAttacks.generation(bot) : lease.generation();
        boolean incident = lease == null;
        if (generation == 0 || !CompanionRuntime.combatAllowed(bot,generation)) return false;
        long started = System.nanoTime();
        try { return BotAttackEffects.withAuthority(bot,generation,() -> tickAuthorized(bot,leader,generation,incident)); }
        finally { if (BossRuntime.get().active(bot)) BossTelemetry.sample(BossTelemetry.Stage.COMBAT,System.nanoTime()-started); }
    }
    private boolean tickAuthorized(Character bot, Character leader, long generation, boolean incident) {
        long now = System.currentTimeMillis();
        CompanionBuild.prepare(bot);
        cureItem(bot,now);
        potion(bot, now);
        if (!bot.isAlive()) return false;
        if(bot.hasDisease(Disease.STUN) || bot.hasDisease(Disease.SEDUCE) || bot.hasDisease(Disease.SEAL)) {
            resourceWarning(bot,now,bot.hasDisease(Disease.SEAL)?"I'm sealed and need a real cure before I can fight."
                    :bot.hasDisease(Disease.SEDUCE)?"I'm being controlled; I can't use my supplies. Help me survive!"
                    :"I'm stunned and can't act yet.");
            return false;
        }
        if (now < nextAction) return true; // Keep combat duty between legitimate casts, not FOLLOW every cooldown tick.
        List<Character> allies = bot.getMap().getCharacters().stream()
                .filter(c -> c.isAlive() && (incident ? BossMonsterController.allied(bot,c) : c.getParty() == bot.getParty())).toList();
        if (cleanse(bot,allies,now)) return true;
        if (heal(bot, allies, now)) return true;
        if (bot.getHp() < bot.getCurrentMaxHp() * .2) {
            resourceWarning(bot, now, "I can't survive here with my remaining supplies. Let's retreat!");
            BossRuntime.get().withdrawal(bot,BossObjective.Outcome.RETREATED,"A companion exhausted its survival reserves.");
            return true;
        }
        if (buff(bot, allies, now)) return true;
        List<Monster> nearby = bot.getMap().getMapObjectsInRange(bot.getPosition(), 1500 * 1500,
                List.of(MapObjectType.MONSTER)).stream().map(m -> (Monster)m)
                .filter(m -> m.isAlive() && !m.getStats().isFriendly())
                .filter(m -> !m.isFake() && BossRuntime.get().targetAllowed(bot,m))
                .filter(m -> !incident || BossMonsterController.targetAllowed(bot,m))
                .filter(m -> BossGeometry.withinLeash(m,leader.getPosition(),700,350)
                        && BossGeometry.distanceSq(m,bot.getPosition()) <= 700 * 700)
                .sorted(Comparator.comparingDouble(m -> BossGeometry.distanceSq(m,bot.getPosition()))).toList();
        if (nearby.isEmpty()) return incident ? approachIncident(bot,generation) : CompanionActivity.engaged(leader);
        Monster nearest = nearby.getFirst();
        BossCombatEvidence.review(bot,nearest);
        if (!CompanionRuntime.combatAllowed(bot,generation)) return true;
        var weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackProfile profile = profile(bot, weapon, nearby.size() > 1,nearest);
        if (profile == null) return false;
        int id = profile.skillFor(weapon);
        StatEffect effect = effect(bot, id);
        if (now < skillCooldown.getOrDefault(id, 0L)) return false;
        boolean left = BossGeometry.aim(nearest).x < bot.getPosition().x;
        MovementCommands.botFaceTowardsPoint(bot, BossGeometry.aim(nearest));
        Rectangle range = effect == null ? null : effect.getAttackBox(bot.getPosition(), left);
        if (range == null) range = new Rectangle(bot.getPosition().x - profile.reachX,
                bot.getPosition().y - profile.reachY, 2 * profile.reachX, 2 * profile.reachY);
        final Rectangle box = range;
        int maxTargets = Math.min(15, effect == null ? 1 : Math.max(1, effect.getMobCount()));
        var targets = nearby.stream().filter(m -> BossGeometry.reaches(box,m,bot.getPosition(),profile.reachY))
                .limit(maxTargets).toList();
        if (targets.isEmpty()) {
            BossCombatEvidence.reach(bot,false);
            if (!CompanionRuntime.combatAllowed(bot,generation)) return true;
            if (BossRuntime.get().active(bot)) {
                var goal=BossPositions.engagement(bot,leader,nearest,profile,effect);
                if (goal.isPresent()) GCMovement.move(bot,goal.get().x,goal.get().y);
                else GCMovement.stop(bot);
            } else GCMovement.move(bot, nearest.getPosition().x, nearest.getPosition().y);
            return true;
        }
        BossCombatEvidence.reach(bot,true);
        boolean magic = profile.route == BotAttackProfile.Route.MAGIC;
        if (targets.stream().allMatch(m -> protectedFrom(m,magic))) { nextAction = now+300; return true; }
        int lines = Math.min(15, effect == null ? 1 : Math.max(1, effect.getAttackCount()));
        if (effect != null && (bot.getMp() < effect.getMpCon() || bot.getHp() <= effect.getHpCon())) return false;
        if (!CompanionRuntime.combatAllowed(bot,generation)) return false;
        if (!payAttack(bot, effect, weapon, profile.route == BotAttackProfile.Route.RANGED ? lines : 0)) {
            resourceWarning(bot, now, "I'm running low on the resources for my attacks. I need a break.");
            BossRuntime.get().withdrawal(bot,BossObjective.Outcome.RETREATED,"Attack resources were exhausted.");
            return false;
        }
        GCMovement.stop(bot);
        Map<Monster, List<Integer>> hits = new LinkedHashMap<>();
        for (Monster target : targets) {
            List<Integer> damageLines = new ArrayList<>();
            for (int i = 0; i < lines; i++) {
                int damage = rollDamage(bot, target, profile, effect);
                damageLines.add(damage);
            }
            hits.put(target, damageLines);
        }
        strike(bot, profile, id, hits, left);
        nextAction = now + Math.max(profile.cooldownMs, id == 0 ? 0 : SkillFactory.getSkill(id).getAnimationTime());
        if (effect != null) skillCooldown.put(id, now + effect.getCooldown() * 1000L);
        return true;
    }
    /** Public incident knowledge authorizes travel to its owned threat, never an out-of-range attack or warp. */
    static boolean approachIncident(Character bot,long generation) {
        if(!CompanionRuntime.combatAllowed(bot,generation)) return false;
        Monster target=bot.getMap().getAllMonsters().stream()
                .filter(m->m.isAlive() && !m.isFake() && !m.isEncounterMarker() && BossMonsterController.targetAllowed(bot,m))
                .min(Comparator.comparingDouble(m->m.getPosition().distanceSq(bot.getPosition()))).orElse(null);
        if(target==null) return false;
        Point goal=bot.getMap().getPointBelow(BossGeometry.aim(target));
        if(goal==null || !bot.getMap().getMapArea().contains(goal)) return false;
        if(CompanionRuntime.combatAllowed(bot,generation) && !GCMovement.isMoving(bot)) GCMovement.move(bot,goal.x,goal.y-1);
        return true;
    }
    private void resourceWarning(Character bot, long now, String message) {
        if (now - lastResourceWarning < 60000) return;
        lastResourceWarning = now;
        soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat(bot, message);
    }
    private static StatEffect effect(Character bot, int id) {
        Skill skill = id == 0 ? null : SkillFactory.getSkill(id);
        int level = skill == null ? 0 : bot.getSkillLevel(skill);
        return level <= 0 ? null : skill.getEffect(level);
    }
    private static BotAttackProfile profile(Character bot, WeaponType weapon, boolean aoe, Monster target) {
        Job[] jobs = Job.values();
        for (int j = jobs.length - 1; j >= 0; j--) {
            if (jobs[j].getId() >= 1000 || !bot.getJob().isA(jobs[j])) continue;
            var kit = CompanionBuild.attackKit(jobs[j], weapon);
            for (BotAttackProfile p : new BotAttackProfile[]{aoe ? kit.aoe() : kit.single(), kit.single()})
                if (p != null && skillAllowed(bot,p.skillFor(weapon)) && (p.skillFor(weapon) == 0 || bot.getSkillLevel(p.skillFor(weapon)) > 0)
                        && (p.skillFor(weapon) == 0 || SkillFactory.getSkill(p.skillFor(weapon)).getElement() == null
                            || target.getElementalEffectiveness(SkillFactory.getSkill(p.skillFor(weapon)).getElement()) != server.life.ElementalEffectiveness.IMMUNE)) return p;
        }
        return BotAttackProfile.basicSwing();
    }
    static boolean protectedFrom(Monster target, boolean magic) {
        return target.isBuffed(magic ? client.status.MonsterStatus.MAGIC_IMMUNITY : client.status.MonsterStatus.WEAPON_IMMUNITY)
                || target.isBuffed(magic ? client.status.MonsterStatus.MAGIC_REFLECT : client.status.MonsterStatus.WEAPON_REFLECT);
    }
    public static boolean skillAllowed(Character bot, int id) {
        var boss = BossRuntime.get().definition(bot);
        if (boss == null || !boss.key().endsWith("balrog") || !boss.restricted() || id == 0) return true;
        int job = id / 10000;
        return job % 10 == 0; // Balrog expedition permits first/second job skills, not third/fourth job.
    }
    static boolean pay(Character bot, StatEffect effect) {
        if (effect == null) return true;
        if (bot.getMp() < effect.getMpCon() || bot.getHp() <= effect.getHpCon() || bot.getMeso() < effect.getMoneyCon()) return false;
        int itemId = effect.getItemCon(), count = Math.max(0, effect.getItemConNo());
        var inventory = bot.getInventory(itemId > 0 ? constants.inventory.ItemConstants.getInventoryType(itemId) : InventoryType.USE);
        inventory.lockInventory();
        try {
            if (count > 0 && inventory.countById(itemId) < count) return false;
            if (!bot.applyHpMpChange(effect.getHpCon(), -effect.getHpCon(), -effect.getMpCon())) return false;
            for (short slot = 1; count > 0 && slot <= inventory.getSlotLimit(); slot++) {
                Item item = inventory.getItem(slot);
                if (item != null && item.getItemId() == itemId) {
                    int take = Math.min(count, item.getQuantity());
                    inventory.removeItem(slot, (short)take, false); count -= take;
                }
            }
            if (effect.getMoneyCon() > 0) bot.gainMeso(-effect.getMoneyCon(), false);
            return true;
        } finally { inventory.unlockInventory(); }
    }
    private static int rollDamage(Character bot, Monster target, BotAttackProfile profile, StatEffect effect) {
        var random = ThreadLocalRandom.current();
        boolean magic = profile.route == BotAttackProfile.Route.MAGIC;
        if (protectedFrom(target,magic)) return 0;
        double base = magic ? Math.ceil((bot.getTotalMagic() * Math.ceil(bot.getTotalMagic() / 1000.0) + bot.getTotalMagic()) / 30.0)
                + Math.ceil(bot.getTotalInt() / 200.0)
                : bot.calculateMaxBaseDamage(bot.getTotalWatk());
        double defense = magic ? target.effectiveStat(target.getStats().getMDDamage(),client.status.MonsterStatus.MAGIC_DEFENSE_UP,client.status.MonsterStatus.MDEF)
                : target.effectiveStat(target.getStats().getPDDamage(),client.status.MonsterStatus.WEAPON_DEFENSE_UP,client.status.MonsterStatus.WDEF);
        double multiplier = effect == null ? 1 : magic ? effect.getMatk() : Math.max(1, effect.getDamage()) / 100.0;
        int skillId = profile.skillFor(BotAttack.resolveEquippedWeaponType(bot));
        if (skillId > 0 && SkillFactory.getSkill(skillId).getElement() != null) {
            multiplier *= switch (target.getElementalEffectiveness(SkillFactory.getSkill(skillId).getElement())) {
                case IMMUNE -> 0; case STRONG -> .5; case WEAK -> 1.5; default -> 1;
            };
        }
        if (multiplier <= 0) return 0;
        boolean missed = random.nextDouble() >= hitChance(bot,target,magic);
        BossCombatEvidence.accuracy(bot,missed);
        if (missed) return 0;
        StatEffect critical = magic ? null : effect(bot,bot.getJob().isA(Job.ASSASSIN)
                ? constants.skills.Assassin.CRITICAL_THROW : bot.getJob().isA(Job.BOWMAN) ? constants.skills.Archer.CRITICAL_SHOT : 0);
        Integer sharpEyes = bot.getBuffedValue(BuffStat.SHARP_EYES);
        double chance = critical == null ? 0 : critical.getProbability();
        if (sharpEyes != null) chance += ((sharpEyes >>> 8) & 0xff)/100.0;
        boolean crit = !magic && random.nextDouble() < Math.min(1,chance);
        if (target.isBuffed(client.status.MonsterStatus.HARD_SKIN) && !crit) return 0;
        if (crit) multiplier += (critical == null ? 0 : Math.max(0,critical.getDamage()-100)/100.0)
                + (sharpEyes == null ? 0 : (sharpEyes & 0xff)/100.0);
        int damage = (int)Math.min(999999, Math.max(1, (base * random.nextDouble(.6, 1.0) - defense * .5) * multiplier));
        return crit ? BotAttackData.encodeCritLine(damage) : damage;
    }
    public static double hitChance(Character bot, Monster target, boolean magic) {
        double accuracy = magic ? Math.floor(bot.getTotalInt()/10.0) + Math.floor(bot.getTotalLuk()/10.0)
                : bot.getTotalDex() * (bot.getJob().getJobNiche() == 4 ? .6 : .8) + bot.getTotalLuk() * .5;
        if (!magic) {
            for (Item item : bot.getInventory(InventoryType.EQUIPPED).list()) if (item instanceof Equip e) accuracy += e.getAcc();
            Integer buff = bot.getBuffedValue(BuffStat.ACC); if (buff != null) accuracy += buff;
        }
        double required = (1.84 + .07 * Math.max(0,target.getLevel()-bot.getLevel())) * Math.max(1,target.getStats().getAvoidability());
        double chance = Math.max(0,Math.min(1,accuracy/required));
        return bot.hasDisease(Disease.DARKNESS) ? chance * .5 : chance;
    }
    private static void strike(Character bot, BotAttackProfile profile, int id, Map<Monster, List<Integer>> hits, boolean left) {
        var weapon = BotAttack.resolveEquippedWeaponType(bot);
        int level = id == 0 ? 0 : bot.getSkillLevel(id);
        int action = BotAttackData.actionFor(id, weapon);
        int facing = left ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        switch (profile.route) {
            case CLOSE -> BotAttackEffects.meleeStrike(bot, hits, id, level, action, facing, profile.speed, profile.hitDelayMs);
            case MAGIC -> BotAttackEffects.magicStrike(bot, hits, id, level, action, facing, profile.speed, profile.hitDelayMs);
            case RANGED -> BotAttackEffects.rangedStrike(bot, hits, id, level, BotAttackData.projectileFor(weapon, bot), action, facing, profile.speed, profile.hitDelayMs);
        }
    }
    boolean potion(Character bot, long now) {
        return potion(bot,now,() -> BotAttackEffects.authorityValid(bot));
    }
    private boolean potion(Character bot, long now, java.util.function.BooleanSupplier valid) {
        if (now < nextPotion || !bot.isAlive() || bot.hasDisease(Disease.SEDUCE)
                || bot.getMap() != null && server.maps.FieldLimit.CANNOTUSEPOTION.check(bot.getMap().getFieldLimit())) return false;
        boolean hp = bot.getHp() < bot.getCurrentMaxHp() * .5;
        boolean mp = bot.getMp() < bot.getCurrentMaxMp() * .3;
        if (!hp && !mp) return false;
        var inventory = bot.getInventory(InventoryType.USE);
        inventory.lockInventory();
        try {
            for (short slot = 1; slot <= inventory.getSlotLimit(); slot++) {
                Item item = inventory.getItem(slot);
                if (item == null || item.getQuantity() <= 0 || item.getItemId() / 10000 != 200) continue;
                StatEffect effect = ItemInformationProvider.getInstance().getItemEffect(item.getItemId());
                if (effect == null || (hp ? effect.getHp() <= 0 && effect.getHpRate() <= 0
                        : effect.getMp() <= 0 && effect.getMpRate() <= 0)) continue;
                if (!valid.getAsBoolean()) return false;
                inventory.removeItem(slot, (short)1, false);
                int hpGain = Math.max(0, effect.getHp()) + (int)(bot.getCurrentMaxHp() * effect.getHpRate());
                int mpGain = Math.max(0, effect.getMp()) + (int)(bot.getCurrentMaxMp() * effect.getMpRate());
                if (bot.hasDisease(Disease.ZOMBIFY)) hpGain /= 2;
                bot.addMPHP(hpGain, mpGain); BossCombatEvidence.potion(bot); nextPotion = now + 1000; return true;
            }
        } finally { inventory.unlockInventory(); }
        return false;
    }
    private boolean cureItem(Character bot, long now) {
        return cureItem(bot,now,() -> BotAttackEffects.authorityValid(bot));
    }
    private boolean cureItem(Character bot,long now,java.util.function.BooleanSupplier valid) {
        if (now < nextPotion || !bot.isAlive() || bot.hasDisease(Disease.SEDUCE)
                || server.maps.FieldLimit.CANNOTUSEPOTION.check(bot.getMap().getFieldLimit())) return false;
        var inventory = bot.getInventory(InventoryType.USE);
        inventory.lockInventory();
        try {
            for (Item item : inventory.list()) {
                if (item.getQuantity() < 1 || item.getItemId()/10000 != 205) continue;
                var cure = ItemInformationProvider.getInstance().getItemEffect(item.getItemId());
                if (cure == null || cure.getCureDebuffs().stream().noneMatch(bot::hasDisease) || !valid.getAsBoolean()) continue;
                inventory.removeItem(item.getPosition(),(short)1,false);
                for (Disease disease : cure.getCureDebuffs()) bot.dispelDebuff(disease);
                BossCombatEvidence.potion(bot); nextPotion = now+1000; return true;
            }
        } finally { inventory.unlockInventory(); }
        return false;
    }
    public boolean travelSupplies(Character bot,long generation) {
        java.util.function.BooleanSupplier valid = () -> server.events.gm.EventBotRuntime.physical(bot)
                && CompanionTaskService.shared().eventLease(bot.getId()).filter(e -> e.committed() && e.generation()==generation).isPresent();
        if (!valid.getAsBoolean() || !bot.isAlive()) return false;
        long now=System.currentTimeMillis();
        boolean cured=cureItem(bot,now,valid); return potion(bot,now,valid) || cured;
    }
    public boolean withdrawalSupplies(Character bot,long generation) {
        java.util.function.BooleanSupplier valid=()->bot.isAlive() && !BossRuntime.get().combatPermitted(bot)
                && CompanionTaskService.shared().task(bot.getId()).filter(t->t.generation()==generation).isPresent();
        if (!valid.getAsBoolean()) return false;
        long now=System.currentTimeMillis();
        boolean cured=cureItem(bot,now,valid);return potion(bot,now,valid)||cured;
    }
    private boolean cleanse(Character bot, List<Character> allies, long now) {
        int id = constants.skills.Priest.DISPEL;
        StatEffect dispel = effect(bot,id);
        if (dispel == null || !skillAllowed(bot,id) || now < skillCooldown.getOrDefault(id,0L)) return false;
        Rectangle box = dispel.getAttackBox(bot.getPosition(),bot.isFacingLeft());
        if (box == null) return false;
        var recipients = allies.stream().filter(c -> box.contains(c.getPosition()) && java.util.stream.Stream.of(
                Disease.CURSE,Disease.DARKNESS,Disease.POISON,Disease.SEAL,Disease.WEAKEN,Disease.SLOW).anyMatch(c::hasDisease)).toList();
        if (recipients.isEmpty() || !BotAttackEffects.authorityValid(bot) || !pay(bot,dispel)) return false;
        for (Character recipient : recipients) if (BotAttackEffects.authorityValid(bot) && recipient.isAlive() && recipient.getMap() == bot.getMap())
            dispel.applyToTarget(bot,recipient); // Learned level's actual chance; Zombify/Seduce remain undispellable.
        bot.getMap().broadcastMessage(PacketCreator.showBuffEffect(bot.getId(),id,1));
        nextAction = now+900; skillCooldown.put(id,now+Math.max(3000,dispel.getCooldown()*1000L)); return true;
    }
    static boolean payAttack(Character bot, StatEffect effect, WeaponType weapon, int quantity) {
        if (quantity <= 0 || bot.getBuffedValue(BuffStat.SOULARROW) != null) return pay(bot, effect);
        int prefix = switch (weapon) { case BOW -> 2060; case CROSSBOW -> 2061; case CLAW -> 2070; case GUN -> 2330; default -> 0; };
        if (prefix == 0) return pay(bot, effect);
        var inventory = bot.getInventory(InventoryType.USE);
        inventory.lockInventory();
        try {
            for (short slot = 1; slot <= inventory.getSlotLimit(); slot++) {
                Item item = inventory.getItem(slot);
                if (item != null && item.getItemId() / 1000 == prefix && item.getQuantity() >= quantity) {
                    if (!pay(bot, effect)) return false;
                    inventory.removeItem(slot, (short)quantity, true); return true;
                }
            }
        } finally { inventory.unlockInventory(); }
        return false;
    }
    boolean heal(Character bot, List<Character> allies, long now) {
        StatEffect heal = effect(bot, Cleric.HEAL);
        if (heal == null || !skillAllowed(bot,Cleric.HEAL) || !BotAttackEffects.authorityValid(bot)) return false;
        Rectangle box = heal.getAttackBox(bot.getPosition(), bot.isFacingLeft());
        if (box == null) return false;
        var hurt = allies.stream().filter(c -> !c.hasDisease(Disease.ZOMBIFY) && box.contains(c.getPosition()) && c.getHp() < c.getCurrentMaxHp() * .7).toList();
        var undead = bot.getMap().getMapObjectsInRange(bot.getPosition(), 1500 * 1500, List.of(MapObjectType.MONSTER)).stream()
                .map(m -> (Monster)m).filter(m -> m.isAlive() && m.getStats().isUndead() && !m.getStats().isFriendly()
                        && !m.isBuffed(client.status.MonsterStatus.MAGIC_IMMUNITY)
                        && !m.isBuffed(client.status.MonsterStatus.MAGIC_REFLECT)
                        && m.getElementalEffectiveness(server.life.Element.HOLY) != server.life.ElementalEffectiveness.IMMUNE
                        && !m.isFake() && BossRuntime.get().targetAllowed(bot,m)
                        && (!BossMonsterController.incidentActor(bot) || BossMonsterController.targetAllowed(bot,m))
                        && BossGeometry.reaches(box,m,bot.getPosition(),700)).limit(Math.max(1, heal.getMobCount())).toList();
        if (hurt.isEmpty() && undead.isEmpty() || !pay(bot, heal)) return false;
        int amount = Math.max(1, (int)(bot.getCurrentMaxHp() * heal.getHp() / (100.0 * Math.max(1, hurt.size()))));
        for (Character ally : hurt) {
            if (!BotAttackEffects.authorityValid(bot) || ally.getMap() != bot.getMap() || !ally.isAlive()) break;
            int before = ally.getHp();
            ally.addHP(ally.hasDisease(Disease.ZOMBIFY) ? -amount : amount);
            CompanionActivity.healing(bot, ally, Math.max(0, ally.getHp() - before));
        }
        if (!undead.isEmpty()) {
            Map<Monster, List<Integer>> hits = new LinkedHashMap<>();
            int undeadDamage = (int)Math.round((bot.getTotalInt() * 4.8 + bot.getTotalLuk() * 4) * bot.getTotalMagic() / 1000.0 * heal.getHp() / 100.0);
            for (Monster target : undead) {
                boolean missed = ThreadLocalRandom.current().nextDouble() >= hitChance(bot,target,true);
                BossCombatEvidence.accuracy(bot,missed);
                var resistance = target.getElementalEffectiveness(server.life.Element.HOLY);
                double multiplier = resistance == server.life.ElementalEffectiveness.WEAK ? 1.5
                        : resistance == server.life.ElementalEffectiveness.STRONG ? .5 : 1;
                int defense = target.effectiveStat(target.getStats().getMDDamage(),client.status.MonsterStatus.MAGIC_DEFENSE_UP,client.status.MonsterStatus.MDEF);
                hits.put(target,List.of(missed || protectedFrom(target,true) || resistance == server.life.ElementalEffectiveness.IMMUNE
                        ? 0 : Math.max(1,(int)((undeadDamage-defense/2.0)*multiplier))));
            }
            strike(bot, BotAttackProfile.magicAoe(Cleric.HEAL, 1), Cleric.HEAL, hits, bot.isFacingLeft());
        } else bot.getMap().broadcastMessage(PacketCreator.showBuffEffect(bot.getId(), Cleric.HEAL, 1));
        nextAction = now + 900; return true;
    }
    boolean buff(Character bot, List<Character> allies, long now) {
        synchronized (BUFF_CAST_LOCK) {
        List<Integer> buffIds = new ArrayList<>(BotBuffConfig.buffsForJob(bot.getJob()));
        buffIds.sort(Comparator.comparingInt(id -> id == constants.skills.Priest.HOLY_SYMBOL ? 0 : 1));
        for (int id : buffIds) {
            if (!skillAllowed(bot,id)) continue;
            // Non-primary Shadow Stars still calls InventoryManipulator through the shared BotClient.
            // Never execute that client-affine branch for a clientless actor.
            if (id == constants.skills.NightLord.SHADOW_STARS) continue;
            StatEffect effect = effect(bot, id);
            if (effect == null || effect.getStatups().isEmpty() || now < skillCooldown.getOrDefault(id, 0L)) continue;
            Rectangle box = effect.getAttackBox(bot.getPosition(), bot.isFacingLeft());
            List<Character> recipients = (effect.isPartyBuff() ? allies : List.of(bot)).stream()
                    .filter(c -> c == bot || box != null && box.contains(c.getPosition()))
                    .filter(c -> needsBuff(c, effect, now)).toList();
            if (recipients.isEmpty() || !pay(bot, effect)) continue;
            for (Character recipient : recipients) if (BotAttackEffects.authorityValid(bot) && recipient.isAlive()
                    && recipient.getMap() == bot.getMap() && effect.applyToTarget(bot, recipient)) {
                for (var stat : effect.getStatups()) CompanionActivity.granted(bot, recipient, id, stat.left, now + effect.getDuration());
            }
            bot.getMap().broadcastMessage(PacketCreator.showBuffEffect(bot.getId(), id, 1));
            nextAction = now + 900; skillCooldown.put(id, now + Math.max(3000, effect.getCooldown() * 1000L)); return true;
        }
        return false;
        }
    }
    static boolean needsBuff(Character target, StatEffect proposed, long now) {
        for (var stat : proposed.getStatups()) {
            Integer value = target.getBuffedValue(stat.left);
            if (value != null && value > stat.right) return false;
        }
        for (var stat : proposed.getStatups()) {
            Integer value = target.getBuffedValue(stat.left);
            if (value == null || value < stat.right) return true;
            StatEffect current = target.getBuffEffect(stat.left);
            Long start = target.getBuffedStarttime(stat.left);
            if (value == stat.right && current != null && start != null
                    && start + current.getDuration() - now <= Math.max(3000, current.getDuration() / 10)) return true;
        }
        return false;
    }
}

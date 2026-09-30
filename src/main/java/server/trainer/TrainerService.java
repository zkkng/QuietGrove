package server.trainer;

import client.Character;
import client.Client;
import client.BuffStat;
import client.SkillFactory;
import client.status.MonsterStatus;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.MapItem;
import tools.PacketCreator;

import java.awt.Point;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Own-server trainer session and server-backed powers. */
public final class TrainerService {
    private static final Logger log = LoggerFactory.getLogger(TrainerService.class);
    private static final TrainerService INSTANCE = new TrainerService();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pairs = new HashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private boolean running;
    private record Pending(Character actor, Client client, long until) {}
    private static final class Session {
        final Character actor;
        final Client client;
        volatile MapleMap map;
        volatile TrainerMobOptions mobs = TrainerMobOptions.OFF;
        volatile TrainerPickupOptions pickup = TrainerPickupOptions.OFF;
        final TrainerPickupOptions.Budget pickupBudget=new TrainerPickupOptions.Budget();
        TrainerRegenOptions regen = TrainerRegenOptions.DEFAULT;
        long nextInspection;
        final Set<Monster> aggroOwned = Collections.newSetFromMap(new WeakHashMap<>());
        long nextAggro;
        final TrainerLease lease;
        final TrainerSessionStats stats;
        long nextObservationRead;
        final TrainerAutoPotion autoPotion = new TrainerAutoPotion();
        volatile TrainerPowerOptions powers = TrainerPowerOptions.OFF;
        volatile long powerExpires;
        TrainerLootOptions loot = TrainerLootOptions.defaults();
        TrainerLootAdvanced lootAdvanced = TrainerLootAdvanced.OFF;
        long nextLootAction, nextFeed, rejectedPickups, acquiredItems, acquiredMesos;
        String vacMode = "front";
        final Map<MapItem, Long> failedPickupRetry = new WeakHashMap<>();
        int stance, direction;
        long moved, looted, hits, pulses, fmaCasts;
        int lastFmaTargets, lastAttackSkill;
        String lastFmaReason = "No FMA swing yet";
        String detail = "Paired; all powers OFF. Combat controls use basic melee only.";
        Session(Pending p, long now) { actor = p.actor; client = p.client; map = actor.getMap(); lease = new TrainerLease(now); stats = new TrainerSessionStats(actor.getLevel(),actor.getExp(),actor.getMeso()); }
    }
    public static TrainerService getInstance() { return INSTANCE; }
    private static boolean permitted(Character a) {
        return Boolean.getBoolean("solo.trainer.enabled") && a != null && a.getClient() != null
                && a.isLoggedinWorld() && a.isAlive() && !soloMapling.ArtificialPlayer.BotHelpers.isBot(a)
                && a.getMap() != null;
    }
    private static boolean valid(Session s, long now) {
        if (!s.lease.alive(now) || s.actor.getClient() != s.client || s.client.getPlayer() != s.actor || !permitted(s.actor)) return false;
        if (s.actor.getMap() != s.map) {
            resetMobs(s);
            s.map = s.actor.getMap();
            s.lease.mapChanged();
            s.autoPotion.off();
            s.lootAdvanced = s.lootAdvanced.paused();
            resetPowers(s);
            log.info("Trainer followed map change character={} map={}", s.actor.getName(), s.map.getId());
        }
        return true;
    }
    private String secret() {
        byte[] bytes = new byte[24]; random.nextBytes(bytes); return HexFormat.of().formatHex(bytes);
    }
    public synchronized void start() throws Exception {
        if (!Boolean.getBoolean("solo.trainer.enabled") || running) return;
        TrainerBridge.start(this);
        TimerManager.getInstance().register(this::tick, 50);
        running = true;
        log.info("Trainer playtest bridge started");
    }
    /** One-click playtest attach: exactly one eligible logged-in character from this game client's LAN IP. */
    public synchronized Map<String, String> attach(String remoteIp) {
        if (!running) throw new IllegalArgumentException("Trainer bridge is not running.");
        Character selected = null;
        for (var world : Server.getInstance().getWorlds()) {
            for (Character actor : world.getPlayerStorage().getAllCharacters()) {
                if (!permitted(actor) || !remoteIp.equals(actor.getClient().getRemoteAddress())) continue;
                if (selected != null) throw new IllegalArgumentException("More than one eligible game character at this IP; close the other client.");
                selected = actor;
            }
        }
        if (selected == null) throw new IllegalArgumentException("No eligible logged-in game character at this IP.");
        revoke(selected);
        long now = System.currentTimeMillis();
        String token = secret();
        Session s = new Session(new Pending(selected, selected.getClient(), now), now);
        sessions.put(token, s);
        log.info("Trainer attached account={} character={} map={} gameIp={}", selected.getAccountID(), selected.getName(), selected.getMapId(), remoteIp);
        Map<String, String> result = status(s); result.put("token", token); return result;
    }
    public synchronized String issuePair(Character actor) throws Exception {
        if (!permitted(actor)) throw new IllegalArgumentException("Trainer requires a logged-in living character.");
        if (!running) start();
        long now = System.currentTimeMillis();
        pairs.entrySet().removeIf(e -> e.getValue().until < now || e.getValue().actor == actor);
        if (pairs.size() >= 128) throw new IllegalArgumentException("Pairing busy; retry later.");
        revoke(actor);
        String code = secret(); pairs.put(code, new Pending(actor, actor.getClient(), now + 60_000)); return code;
    }
    public synchronized Map<String, String> pair(String code) {
        Pending p = pairs.remove(code);
        long now = System.currentTimeMillis();
        if (p == null || p.until <= now || p.actor.getClient() != p.client || p.client.getPlayer() != p.actor || !permitted(p.actor))
            throw new IllegalArgumentException("Pair code invalid/expired or game session changed.");
        if (sessions.size() >= 8) throw new IllegalArgumentException("Trainer session budget exhausted.");
        revoke(p.actor);
        String token = secret(); Session s = new Session(p, now); sessions.put(token, s);
        Map<String, String> result = status(s); result.put("token", token); return result;
    }
    public synchronized void revoke(Character actor) {
        sessions.entrySet().removeIf(e -> { if (e.getValue().actor != actor) return false;
            synchronized (e.getValue()) { resetPowers(e.getValue()); e.getValue().lease.revoke(); } return true; });
        pairs.entrySet().removeIf(e -> e.getValue().actor == actor);
    }
    public Map<String, String> request(String token, String action, Map<String, String> fields) {
        Session s = sessions.get(token);
        if (s == null) throw new IllegalArgumentException("Session not paired.");
        synchronized (s) {
            long now = System.currentTimeMillis();
            if (!valid(s, now)) { resetPowers(s); s.lease.off(); sessions.remove(token, s); log.info("Trainer session suspended character={} map={}", s.actor.getName(), s.actor.getMapId()); throw new IllegalArgumentException("Session expired/suspended; click INJECT HAX again after map/channel/death/logout."); }
            if (!s.lease.allowRequest(now)) throw new IllegalArgumentException("Request budget exceeded.");
            switch (action) {
                case "status" -> s.lease.renew(now);
                case "off" -> { resetPowers(s); s.autoPotion.off(); s.lease.revoke(); sessions.remove(token, s); s.detail = "All powers OFF; click INJECT HAX to resume."; log.info("Trainer panic/off character={}", s.actor.getName()); }
                case "powers" -> {
                    var desired = TrainerPowerOptions.parse(fields);
                    for (int id : desired.cooldownSkills()) {
                        var skill = SkillFactory.getSkill(id);
                        if (skill == null || s.actor.getSkillLevel(skill) <= 0 || skill.getEffect(s.actor.getSkillLevel(skill)).getCooldown() <= 0)
                            throw new IllegalArgumentException("Cooldown control requires learned cooldown skills");
                    }
                    var previous = s.powers; s.powers = desired; s.lease.renew(now);
                    Set<Integer> changed = new HashSet<>(previous.cooldownSkills()); changed.addAll(desired.cooldownSkills());
                    resendCooldowns(s.actor, changed, desired);
                }
                case "mobtools" -> {
                    var desired = TrainerMobOptions.parse(fields);
                    if (desired.pointVac() && !s.map.getMapArea().contains(desired.x(), desired.y()))
                        throw new IllegalArgumentException("Saved point must be inside this map");
                    resetMobs(s); s.mobs = desired; s.lease.renew(now);
                }
                case "regenoptions" -> { s.regen=TrainerRegenOptions.parse(fields); s.lease.renew(now); }
                case "pickupoptions" -> { s.pickup=TrainerPickupOptions.parse(fields); s.lease.renew(now); }
                case "inspect" -> {
                    if(now<s.nextInspection) throw new IllegalArgumentException("Refresh inspector at most twice per second");
                    s.nextInspection=now+500; s.lease.renew(now); s.powerExpires=s.lease.expiresAt();
                    return TrainerInspector.snapshot(s.actor,fields);
                }
                case "loottools" -> { s.lootAdvanced = TrainerLootAdvanced.parse(fields); s.lease.renew(now); }
                case "cleanse" -> {
                    if (!fields.isEmpty()) throw new IllegalArgumentException("Cleanse takes no fields");
                    for (var disease : s.powers.immunity()) s.actor.dispelDebuff(disease);
                    s.detail = "Selected statuses cleansed";
                }
                case "refill" -> {
                    if (!fields.keySet().equals(Set.of("resource"))) throw new IllegalArgumentException("Choose HP or MP");
                    if (fields.get("resource").equals("hp")) s.actor.addHP(s.actor.getCurrentMaxHp());
                    else if (fields.get("resource").equals("mp")) s.actor.addMP(s.actor.getCurrentMaxMp());
                    else throw new IllegalArgumentException("Choose HP or MP");
                    s.detail = "Explicit resource refill applied";
                }
                case "autopotion" -> { s.autoPotion.configure(TrainerProfile.potions(fields)); s.lease.renew(now); }
                case "profile", "profileValidate" -> {
                    var desired = TrainerProfile.parse(fields);
                    validateProfile(s,desired);
                    if (action.equals("profile")) {
                        var previous=s.powers;
                        desired.core().apply(s.lease,now);
                        s.vacMode=desired.core().vacMode(); s.loot=desired.core().loot();
                        s.autoPotion.configure(desired.potions()); s.lootAdvanced=desired.loot(); s.pickup=desired.pickup(); s.regen=desired.regen();
                        resetMobs(s); s.mobs=desired.mobs(); s.powers=desired.powers();
                        Set<Integer> changed=new HashSet<>(previous.cooldownSkills()); changed.addAll(s.powers.cooldownSkills());
                        try { resendCooldowns(s.actor,changed,s.powers); }
                        catch (RuntimeException failure) { resetPowers(s); s.autoPotion.off(); s.lootAdvanced=s.lootAdvanced.paused(); s.lease.off(); throw failure; }
                        s.detail="Complete server profile applied";
                    } else s.lease.renew(now);
                }
                case "observations" -> {
                    if (now < s.nextObservationRead) throw new IllegalArgumentException("Refresh observations at most once every five seconds");
                    s.nextObservationRead=now+5000; s.lease.renew(now); s.powerExpires=s.lease.expiresAt();
                    return TrainerSocialMemory.recent(s.actor.getId(),now);
                }
                case "configure" -> {
                    for (String key : fields.keySet()) if (!Set.of("vac", "vacMode", "itemVac", "mesoVac", "lootOnKey", "lootRadius", "lootBatch", "lootOrder", "includeIds", "excludeIds", "minMeso", "maxMeso", "fma", "fmaDamage", "fmaOneHit", "rapid", "hpGod", "hpRegen", "mpRegen", "interval").contains(key)) throw new IllegalArgumentException("Unknown setting.");
                    int interval = Integer.parseInt(fields.getOrDefault("interval", "300"));
                    int fmaDamage = Integer.parseInt(fields.getOrDefault("fmaDamage", "1"));
                    boolean fmaOneHit = flag(fields, "fmaOneHit");
                    if (fmaDamage < 1 || fmaDamage > 100) throw new IllegalArgumentException("FMA multiplier must be 1..100.");
                    String vacMode = fields.getOrDefault("vacMode", "front");
                    if (!Set.of("front", "left wall", "right wall").contains(vacMode)) throw new IllegalArgumentException("Unknown Mob Vac mode.");
                    int radius = Integer.parseInt(fields.getOrDefault("lootRadius", "0"));
                    int batch = Integer.parseInt(fields.getOrDefault("lootBatch", "8"));
                    TrainerLootOptions options = TrainerLootOptions.parse(fields.getOrDefault("includeIds", ""), fields.getOrDefault("excludeIds", ""),
                            fields.getOrDefault("lootOrder", "nearest"), Integer.parseInt(fields.getOrDefault("minMeso", "0")),
                            Integer.parseInt(fields.getOrDefault("maxMeso", Integer.toString(Integer.MAX_VALUE))));
                    s.lease.configure(flag(fields, "vac"), flag(fields, "itemVac"), fields.containsKey("mesoVac") ? flag(fields, "mesoVac") : flag(fields, "itemVac"),
                            flag(fields, "lootOnKey"), radius, batch, flag(fields, "fma"), flag(fields, "rapid"),
                            flag(fields, "hpGod"), flag(fields, "hpRegen"), flag(fields, "mpRegen"), interval, now);
                    s.lease.fmaPower(fmaDamage, fmaOneHit);
                    s.vacMode = vacMode;
                    s.loot = options;
                    s.detail = "Applied. FMA follows a real close-range swing; attack input remains yours.";
                    log.info("Trainer applied character={} map={} mobVac={} itemVac={} mesoVac={} lootOnKey={} lootRadius={} lootBatch={} fma={} rapid={} interval={}", s.actor.getName(), s.actor.getMapId(), s.lease.vac(), s.lease.itemVac(), s.lease.mesoVac(), s.lease.lootOnKey(), radius, batch, s.lease.fma(), s.lease.rapid(), interval);
                }
                case "lootPulse" -> {
                    if (!fields.isEmpty()) throw new IllegalArgumentException("Loot pulse takes no fields.");
                    itemVac(s, now);
                }
                default -> throw new IllegalArgumentException("Unknown operation.");
            }
            if (s.lease.alive(now)) s.powerExpires = s.lease.expiresAt();
            return status(s);
        }
    }
    private static void validateProfile(Session s,TrainerProfile profile) {
        if (profile.mobs().pointVac() && (profile.pointMap()!=s.map.getId() || !s.map.getMapArea().contains(profile.mobs().x(),profile.mobs().y())))
            throw new IllegalArgumentException("Saved point belongs to a different map or is outside this map");
        for (int id:profile.powers().cooldownSkills()) {
            var skill=SkillFactory.getSkill(id);
            if (skill==null || s.actor.getSkillLevel(skill)<=0 || skill.getEffect(s.actor.getSkillLevel(skill)).getCooldown()<=0)
                throw new IllegalArgumentException("Cooldown control requires learned cooldown skills");
        }
    }
    /** Called only after exactly-once map removal and killBy. No session/stat locks here. */
    public void onMonsterKilled(Character actor,Monster monster) {
        if (actor==null || monster==null || monster.getHp()>0) return;
        long now=System.currentTimeMillis();
        for (Session s:sessions.values()) if (s.actor==actor && now<s.powerExpires && actor.getMap()==s.map
                && actor.getClient()==s.client && s.client.getPlayer()==actor && actor.isLoggedinWorld()) {
            s.stats.finishingKills.incrementAndGet(); return;
        }
    }
    private static boolean flag(Map<String, String> f, String name) {
        String v = f.getOrDefault(name, "0"); if (!v.equals("0") && !v.equals("1")) throw new IllegalArgumentException("Flags must be 0 or 1."); return v.equals("1");
    }
    private Map<String, String> status(Session s) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("profileReady","1");
        s.stats.status(r,s.actor.getLevel(),s.actor.getExp(),s.actor.getMeso(),s.pulses);
        s.autoPotion.status(r);
        s.powers.status(r);
        s.lootAdvanced.status(r);
        s.mobs.status(r); s.pickup.status(r); s.regen.status(r); r.put("inspectorReady","1");
        r.put("rejectedPickups", Long.toString(s.rejectedPickups)); r.put("acquiredItems", Long.toString(s.acquiredItems)); r.put("acquiredMesos", Long.toString(s.acquiredMesos));
        r.put("protocol", "SoloMapling-Trainer-v2"); r.put("character", s.actor.getName()); r.put("map", Integer.toString(s.map.getId()));
        r.put("vac", s.lease.vac() ? "1" : "0"); r.put("vacMode", s.vacMode); r.put("itemVac", s.lease.itemVac() ? "1" : "0");
        r.put("mesoVac", s.lease.mesoVac() ? "1" : "0"); r.put("lootOnKey", s.lease.lootOnKey() ? "1" : "0");
        r.put("lootRadius", Integer.toString(s.lease.lootRadius())); r.put("lootBatch", Integer.toString(s.lease.lootBatch()));
        r.put("lootOrder", s.loot.order); r.put("includeIds", s.loot.includeText); r.put("excludeIds", s.loot.excludeText);
        r.put("minMeso", Integer.toString(s.loot.minMeso)); r.put("maxMeso", Integer.toString(s.loot.maxMeso));
        r.put("fma", s.lease.fma() ? "1" : "0"); r.put("rapid", s.lease.rapid() ? "1" : "0");
        r.put("fmaDamage", Integer.toString(s.lease.fmaDamage())); r.put("fmaOneHit", s.lease.fmaOneHit() ? "1" : "0");
        r.put("hpGod", s.lease.hpGod() ? "1" : "0"); r.put("hpRegen", s.lease.hpRegen() ? "1" : "0"); r.put("mpRegen", s.lease.mpRegen() ? "1" : "0");
        r.put("interval", Integer.toString(s.lease.interval())); r.put("moved", Long.toString(s.moved)); r.put("looted", Long.toString(s.looted)); r.put("hits", Long.toString(s.hits));
        r.put("pulses", Long.toString(s.pulses)); r.put("fmaCasts", Long.toString(s.fmaCasts));
        r.put("lastFmaTargets", Integer.toString(s.lastFmaTargets)); r.put("lastAttackSkill", Integer.toString(s.lastAttackSkill));
        r.put("lastFmaReason", s.lastFmaReason);
        r.put("detail", s.detail); return r;
    }
    /** Reject over-budget rapid packets before costs, damage, or broadcasts. */
    public boolean allowClientAttack(Character actor) {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) if (s.actor == actor) synchronized (s) {
            if (!valid(s, now)) continue;
            if (!s.lease.allowClientAttack(now)) {
                s.detail = "Rapid packet held by " + s.lease.interval() + " ms action budget.";
                return false;
            }
        }
        return true;
    }
    /** Called once after the normal real-cast resource and combat pipeline completes. */
    public void onAcceptedAttack(Character actor, AttackInfo attack, int acceptedLines) {
        if (!attack.trainerAccepted || attack.targets == null || acceptedLines < 1 || acceptedLines > 15) return;
        attack.trainerAccepted = false; // consume receipt; the same attack cannot expand twice
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) if (s.actor == actor) synchronized (s) {
            if (!valid(s, now)) { s.lease.off(); continue; }
            s.stance = attack.stance; s.direction = attack.direction; s.lease.acceptedAttack(now);
            s.pulses++; s.lastAttackSkill = attack.skill;
            if (s.lease.fma()) {
                try {
                    s.lastFmaTargets = hit(s, attack, new HashSet<>(attack.targets.keySet()), acceptedLines);
                    s.fmaCasts++;
                    try { TrainerBotReactions.onMapAttack(actor, s.map, actor.getPosition(), s.lastFmaTargets); }
                    catch (RuntimeException reactionFailure) {
                        log.warn("Trainer bot attack reaction failed without affecting combat character={}", actor.getName(), reactionFailure);
                    }
                    s.detail = "FMA swing " + s.fmaCasts + ": " + s.lastFmaTargets
                            + " added mobs (skill " + attack.skill + ", " + attack.targets.size() + " local targets). " + s.lastFmaReason;
                    if (s.fmaCasts <= 3 || s.fmaCasts % 20 == 0)
                        log.info("Trainer FMA cast character={} map={} skill={} clientTargets={} expandedTargets={} totalHits={}",
                                actor.getName(), s.map.getId(), attack.skill, attack.targets.size(), s.lastFmaTargets, s.hits);
                } catch (RuntimeException failure) {
                    s.lease.disableFma(); s.detail = "FMA paused after a server error; other powers remain active. See server log.";
                    log.error("Trainer FMA failed character={} map={} skill={}", actor.getName(), s.map.getId(), attack.skill, failure);
                }
            }
        }
    }
    private void tick() {
        long now = System.currentTimeMillis();
        for (var entry : sessions.entrySet()) {
            Session s = entry.getValue();
            synchronized (s) {
                if (!valid(s, now)) { resetPowers(s); s.lease.off(); sessions.remove(entry.getKey(), s); log.info("Trainer lease expired or session changed character={}", s.actor.getName()); continue; }
                if (s.lease.takeVac(now)) try { vac(s); }
                catch (RuntimeException failure) {
                    s.lease.disableVac(); s.detail = "Mob Vac paused after a server error; other powers remain active.";
                    log.error("Trainer Mob Vac paused character={}", s.actor.getName(), failure);
                }
                if (s.lease.takeItemVac(now) || s.lootAdvanced.automated() || s.pickup.automated()) try { itemVac(s, now); }
                catch (RuntimeException failure) {
                    s.lease.disableItemVac(); s.lootAdvanced = s.lootAdvanced.paused(); s.detail = "Loot automation paused after a server error; other powers remain active.";
                    log.error("Trainer loot vac paused character={}", s.actor.getName(), failure);
                }
                if (s.lease.takeRegen(now,s.regen.interval())) try {
                        if (s.lease.hpRegen()) s.actor.addHP(TrainerRegenOptions.amount(s.actor.getCurrentMaxHp(),s.regen.hpPercent()));
                        if (s.lease.mpRegen()) s.actor.addMP(TrainerRegenOptions.amount(s.actor.getCurrentMaxMp(),s.regen.mpPercent()));
                } catch (RuntimeException failure) {
                    s.lease.disableRegen(); s.detail = "Regen paused after a server error; other powers remain active.";
                    log.error("Trainer regen paused character={}", s.actor.getName(), failure);
                }
                // Rapid is driven by real client input in SoloTrainer; no server-only phantom swings.
                try { s.autoPotion.tick(s.actor, now); }
                catch (RuntimeException failure) {
                    s.autoPotion.off(); s.detail = "Auto potion stopped after a server error";
                    log.error("Trainer auto potion stopped character={}", s.actor.getName(), failure);
                }
                try { aggroMobs(s, now); }
                catch (RuntimeException failure) { resetMobs(s); log.error("Trainer mob controls stopped actor={}",s.actor.getId(),failure); }
                try { feedPet(s, now); }
                catch (RuntimeException failure) {
                    s.lootAdvanced = s.lootAdvanced.paused(); s.detail = "Pet automation stopped after a server error";
                    log.error("Trainer pet feeding stopped character={}", s.actor.getName(), failure);
                }
            }
        }
    }
    /** Query for an owned character's trainer HP shield before applying a hit. */
    public boolean hpGod(Character actor) {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) if (s.actor == actor) synchronized (s) {
            return valid(s, now) && s.lease.hpGod();
        }
        return false;
    }
    private static boolean eligible(Monster m) {
        return TrainerMobOptions.ordinary(m)
                && !m.isBuffed(MonsterStatus.WEAPON_IMMUNITY) && !m.isBuffed(MonsterStatus.WEAPON_REFLECT)
                && !m.isBuffed(MonsterStatus.MAGIC_IMMUNITY) && !m.isBuffed(MonsterStatus.MAGIC_REFLECT);
    }
    // Called from existing character/stat locks: never acquire the trainer session monitor here.
    private TrainerPowerOptions powers(Character actor) {
        if (actor == null) return TrainerPowerOptions.OFF;
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) if (s.actor == actor && now < s.powerExpires
                && s.client == actor.getClient() && s.client.getPlayer() == actor
                && actor.getMap() == s.map && actor.isLoggedinWorld() && actor.isAlive()
                && !soloMapling.ArtificialPlayer.BotHelpers.isBot(actor)) return s.powers;
        return TrainerPowerOptions.OFF;
    }
    public boolean noMpCost(Character actor) { return powers(actor).noMpCost(); }
    public boolean noAmmo(Character actor) { return powers(actor).noAmmo(); }
    public boolean immune(Character actor, client.Disease disease) { return powers(actor).immunity().contains(disease); }
    public boolean cooldownBypass(Character actor, int skill) { return powers(actor).cooldown(skill); }
    public int cooldownDisplay(Character actor, int skill, int naturalSeconds) { return cooldownBypass(actor,skill) ? 0 : naturalSeconds; }
    private static void resendCooldowns(Character actor, Set<Integer> skills, TrainerPowerOptions options) {
        if (actor.getClient() == null || actor.getClient().getPlayer() != actor) return;
        long now = Server.getInstance().getCurrentTime();
        Map<Integer,Integer> remaining = new HashMap<>();
        for (var cooldown : actor.getAllCooldowns()) if (skills.contains(cooldown.skillId))
            remaining.put(cooldown.skillId, (int)Math.min(Short.MAX_VALUE, Math.max(0, (cooldown.startTime + cooldown.length - now + 999) / 1000)));
        for (int id : skills) actor.sendPacket(PacketCreator.skillCooldown(id, options.cooldown(id) ? 0 : remaining.getOrDefault(id,0)));
    }
    private static void resetPowers(Session s) {
        resetMobs(s); s.pickup=s.pickup.paused();
        var old = s.powers; s.powers = TrainerPowerOptions.OFF; s.powerExpires = 0;
        if (old.zeroCooldown()) try { resendCooldowns(s.actor, old.cooldownSkills(), TrainerPowerOptions.OFF); }
        catch (RuntimeException disconnected) { log.warn("Trainer cooldown display reset could not reach character={}", s.actor.getId()); }
    }
    /** Adjust only already parsed real attacks, before their normal broadcast/damage pipeline. */
    public void prepareAttack(Character actor, AttackInfo attack) {
        var options = powers(actor);
        if (options.damageMultiplier() == 1 && !options.oneHit() && !options.accuracy() && options.roll().equals("normal")) return;
        if (attack.targets == null) return;
        for (var entry : attack.targets.entrySet()) {
            Monster target = actor.getMap().getMonsterByOid(entry.getKey());
            if (target == null || !eligible(target) || target.isBuffed(MonsterStatus.MAGIC_IMMUNITY)
                    || target.isBuffed(MonsterStatus.MAGIC_REFLECT)) continue;
            List<Integer> damage = new ArrayList<>(); int line = 0;
            for (int original : entry.getValue().damageLines()) damage.add(options.damage(original, attack.trainerMaxDamage, target.getHp(), line++));
            entry.setValue(new AttackTarget(entry.getValue().delay(), damage));
        }
    }
    private static void resetMobs(Session s) {
        s.mobs = TrainerMobOptions.OFF;
        for (Monster mob : s.aggroOwned) try {
            if (mob.isAlive() && mob.getController() == s.actor) mob.aggroRedirectController();
        } catch (RuntimeException failure) { log.warn("Trainer aggro reset could not reach mob={}",mob.getObjectId()); }
        s.aggroOwned.clear();
    }
    private void aggroMobs(Session s,long now) {
        if (!s.mobs.aggro() || now < s.nextAggro) return;
        s.nextAggro = now + 1000;
        int count = 0;
        for (var object : s.map.getMonsters()) {
            Monster mob = (Monster)object;
            if (!TrainerMobOptions.ordinary(mob) || !s.mobs.accepts(mob.getId(),s.actor.getPosition(),mob.getPosition())) continue;
            if (++count > 100) break;
            if (mob.getController() != s.actor || !mob.isControllerHasAggro()) {
                mob.aggroSwitchController(s.actor,true); s.aggroOwned.add(mob);
            }
        }
    }
    /** Read-only overlay query from packet handlers; never acquire the session monitor. */
    private boolean mobFlag(Monster monster,boolean freeze) {
        if (!TrainerMobOptions.ordinary(monster)) return false;
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            TrainerMobOptions options = s.mobs;
            if ((freeze ? options.freeze() : options.disarm()) && now < s.powerExpires && monster.getMap() == s.map
                    && s.actor.getMap() == s.map && s.actor.getClient() == s.client && s.client.getPlayer() == s.actor
                    && s.actor.isLoggedinWorld() && s.actor.isAlive()
                    && options.accepts(monster.getId(),s.actor.getPosition(),monster.getPosition())) return true;
        }
        return false;
    }
    public boolean monsterFrozen(Monster monster) { return mobFlag(monster,true); }
    public boolean monsterDisarmed(Monster monster) { return mobFlag(monster,false); }
    private void vac(Session s) {
        int count = 0, movedThisSweep = 0;
        Point actor = s.actor.getPosition();
        int facing = s.actor.isFacingLeft() ? -1 : 1;
        for (var o : s.map.getMonsters()) {
            if (count >= 100) break;
            Monster m = (Monster) o; if (!TrainerMobOptions.ordinary(m) || !eligible(m) || !s.mobs.accepts(m.getId(),actor,m.getPosition())) continue;
            Point target = s.map.getPointBelow(s.mobs.destination(actor,m.getPosition(),facing,count,s.map.getMapArea(),s.vacMode));
            if (target == null || Math.abs(target.y - (s.mobs.pointVac() ? s.mobs.y() : actor.y)) > 200 || target.x < Short.MIN_VALUE || target.x > Short.MAX_VALUE || target.y < Short.MIN_VALUE || target.y > Short.MAX_VALUE) continue;
            if (s.mobs.pullStep() > 0 && m.getPosition().distanceSq(target) > (long)s.mobs.pullStep()*s.mobs.pullStep()) continue;
            if (m.getPosition().distanceSq(target) > 400) { m.resetMobPosition(target); s.moved++; movedThisSweep++; }
            count++;
        }
        try { TrainerBotReactions.onMobRelocated(s.actor, s.map, actor, movedThisSweep); }
        catch (RuntimeException reactionFailure) {
            log.warn("Trainer mob relocation reaction failed without affecting vac character={}", s.actor.getName(), reactionFailure);
        }
    }
    private void itemVac(Session s, long now) {
        if (now < s.nextLootAction || (!s.lease.itemVac() && !s.lease.mesoVac() && !s.lootAdvanced.automated() && !s.pickup.automated())) return;
        s.nextLootAction = now + (s.pickup.automated()?s.pickup.interval():500); // All manual, auto, pet and bridge pulses share one bounded sweep budget.
        if (s.actor.isChangingMaps() || s.client.isInTransition() || s.actor.getTrade()!=null || s.actor.getShop()!=null || s.client.getCM()!=null) return;
        int attempted=0, items=0, mesos=0;
        int maximum=s.pickup.automated()?(s.pickup.burst()?s.pickup.batch():1):s.lease.lootBatch();
        maximum=Math.min(maximum,s.pickupBudget.available(now)); if(maximum==0) return;
        var advanced=s.lootAdvanced;
        List<MapItem> drops = new ArrayList<>();
        for (var object : s.map.getItems()) if (object instanceof MapItem item) drops.add(item);
        Point actor = s.actor.getPosition();
        Comparator<MapItem> order = switch(s.loot.order) {
            case "newest" -> Comparator.comparingLong(MapItem::getDropTime).reversed();
            case "highest value" -> Comparator.comparingLong(TrainerLootAdvanced::value).reversed();
            case "owner stake first" -> Comparator.comparingInt((MapItem item)->item.getOwnerId()==s.actor.getId()?0:1).thenComparingDouble(item->actor.distanceSq(item.getPosition()));
            default -> Comparator.comparingDouble(item->actor.distanceSq(item.getPosition()));
        };
        drops.sort(order.thenComparingInt(MapItem::getObjectId));
        for (MapItem item : drops) {
            if (attempted >= maximum) break;
            boolean meso=item.getMeso()>0, wantPet=meso?advanced.petMesos():advanced.petItems();
            boolean remote=wantPet || (meso?s.lease.mesoVac():s.lease.itemVac());
            if (!remote && !advanced.nearbyAuto() && !s.pickup.automated()) continue;
            if (meso ? mesos>=advanced.mesoQuota() : items>=advanced.itemQuota()) continue;
            int petIndex=-1; Point anchor=actor;
            if (wantPet) {
                var pet=s.actor.getPet(advanced.petIndex());
                if (pet==null || !pet.isSummoned() || pet.getPos()==null) { s.detail="Pet loot paused: selected pet is not summoned"; continue; }
                if (meso?!s.actor.isEquippedMesoMagnet():!s.actor.isEquippedItemPouch()) { s.detail="Pet loot paused: real magnet/pouch required"; continue; }
                if (s.actor.isEquippedPetItemIgnore() && s.actor.getExcludedItems().contains(meso?Integer.MAX_VALUE:item.getItemId())) continue;
                petIndex=advanced.petIndex(); anchor=pet.getPos();
            }
            int radius=remote?s.lease.lootRadius():180;
            if ((radius>0 && anchor.distanceSq(item.getPosition())>(long)radius*radius) || !s.loot.accepts(item) || !advanced.accepts(item) || !s.pickup.accepts(item)
                    || item.isPickedUp() || item.pickupExpired(now) || now-item.getDropTime()<s.pickup.age(item) || !item.canBePickedBy(s.actor)
                    || s.map.getMapObject(item.getObjectId())!=item || s.failedPickupRetry.getOrDefault(item,0L)>now) continue;
            if(!s.pickupBudget.take(now)) break;
            attempted++; if(meso) mesos++; else items++;
            Point pickupFrom=new Point(item.getPosition()); boolean committed;
            int beforeMesos=s.actor.getMeso(); int quantity=meso?0:item.getItem().getQuantity();
            item.lockItem();
            try {
                boolean alreadyPickedUp=item.isPickedUp();
                if(!alreadyPickedUp) {
                    if(item.getVenueAssetId()!=null) TrainerVenuePickup.pickup(s.actor,item,petIndex,remote);
                    else s.actor.pickupItem(item,petIndex);
                }
                committed=!alreadyPickedUp&&item.isPickedUp()&&item.getCollectedByCharacterId()==s.actor.getId();
            } finally { item.unlockItem(); }
            if(committed) {
                s.looted++; s.acquiredItems+=quantity;
                if(meso) s.acquiredMesos+=Math.max(0L,(long)s.actor.getMeso()-beforeMesos);
                s.failedPickupRetry.remove(item);
                try { TrainerBotReactions.onCommittedPickup(s.actor,s.map,item,pickupFrom,petIndex>=0); }
                catch(RuntimeException failure) { log.warn("Trainer reaction failed after committed pickup actor={}",s.actor.getName(),failure); }
            } else { s.rejectedPickups++; s.failedPickupRetry.put(item,now+2000); }
        }
    }
    private void feedPet(Session s,long now) {
        var options=s.lootAdvanced;
        if(!options.feeder() || now<s.nextFeed) return;
        s.nextFeed=now+5000;
        if(s.actor.isChangingMaps()||s.client.isInTransition()||s.actor.getTrade()!=null||s.actor.getShop()!=null||s.client.getCM()!=null) return;
        var pet=s.actor.getPet(options.petIndex());
        if(pet==null||!pet.isSummoned()) { s.detail="Pet feeding paused: selected pet is not summoned"; return; }
        if(pet.getFullness()>options.feedThreshold()) return;
        var inventory=s.actor.getInventory(client.inventory.InventoryType.USE);
        synchronized(s.actor) {
            inventory.lockInventory();
            try {
                var food=inventory.findById(2120000);
                if(food==null||food.getQuantity()<1) {
                    s.lootAdvanced=new TrainerLootAdvanced(options.nearbyAuto(),options.petItems(),options.petMesos(),options.petIndex(),false,
                            options.feedThreshold(),options.name(),options.category(),options.minValue(),options.source(),options.itemQuota(),options.mesoQuota());
                    s.detail="Pet feeder stopped: out of real Pet Food (2120000)"; return;
                }
                client.inventory.manipulator.InventoryManipulator.removeFromSlot(s.client,client.inventory.InventoryType.USE,food.getPosition(),(short)1,false);
                pet.gainTamenessFullness(s.actor,1,30,1); s.detail="Selected pet fed one real Pet Food";
            } finally { inventory.unlockInventory(); }
        }
    }
    /** Lock-free because the pickup caller owns a drop lock. Only tagged finite scenarios can opt in. */
    public int minimumPickupAge(Character actor,MapItem drop) {
        if(actor==null || drop==null) return 400;
        long now=System.currentTimeMillis();
        for(Session s:sessions.values()) if(s.actor==actor && now<s.powerExpires && s.actor.getMap()==s.map
                && s.client==actor.getClient() && s.client.getPlayer()==actor && actor.isAlive() && actor.isLoggedinWorld())
            return s.pickup.age(drop);
        return 400;
    }
    /** Only the active, own-server item-vac session may waive venue pickup distance. */
    public boolean venueRemotePickupAllowed(Character actor) {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) if (s.actor == actor) synchronized (s) {
            if (valid(s, now) && (s.lease.itemVac() || s.lootAdvanced.petItems())) return true;
        }
        return false;
    }
    /** A real client loot packet can trigger one sweep when key mode is selected. */
    public void onLootButton(Character actor) {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) if (s.actor == actor) synchronized (s) {
            if (!valid(s, now) || !s.lease.lootOnKey()) continue;
            try { itemVac(s, now); }
            catch (RuntimeException failure) { s.lease.disableItemVac(); log.error("Trainer loot-key action paused character={}", actor.getName(), failure); }
        }
    }
    private int hit(Session s, AttackInfo attack, Set<Integer> excluded, int lines) {
        if (!TrainerFmaPolicy.supported(attack.skill) || s.map.getEventInstance() != null) {
            s.lastFmaReason = "Special skill or event instance keeps its ordinary target rules"; return 0;
        }
        if (s.actor.getBuffEffect(BuffStat.MORPH) != null && s.actor.getBuffEffect(BuffStat.MORPH).isMorphWithoutAttack()) {
            s.lastFmaReason = "Morph cannot attack"; return 0;
        }
        long base = Math.min(Integer.MAX_VALUE / 2L, Math.max(0L, attack.trainerMaxDamage));
        int max = (int) Math.min(Integer.MAX_VALUE / 2L, base * s.lease.fmaDamage());
        if (max <= 0) { s.lastFmaReason = "No accepted attack damage"; return 0; }
        if (attack.skill != 0) {
            var skill = SkillFactory.getSkill(attack.skill);
            if (skill == null || s.actor.getSkillLevel(skill) <= 0) { s.lastFmaReason = "Skill not learned"; return 0; }
        }
        LinkedHashMap<Integer, AttackTarget> batch = new LinkedHashMap<>();
        long hitsBefore = s.hits;
        List<Monster> victims = new ArrayList<>();
        for (var o : s.map.getMonsters()) {
            Monster m = (Monster) o;
            if (victims.size() >= 100) break;
            if (!eligible(m) || excluded.contains(m.getObjectId())) continue;
            victims.add(m);
        }
        for (Monster m : victims) {
            List<Integer> damage = new ArrayList<>(lines);
            if (s.lease.fmaOneHit()) {
                damage.add(Math.max(1, m.getHp()));
                for (int i = 1; i < lines; i++) damage.add(0);
            } else {
                int perLineMax = Math.min(199_999, max);
                for (int i = 0; i < lines; i++) damage.add(Math.max(1, perLineMax / 2 + random.nextInt(Math.max(1, perLineMax - perLineMax / 2 + 1))));
            }
            var options = powers(s.actor);
            if (options.damageMultiplier()!=1 || options.oneHit() || options.accuracy() || !options.roll().equals("normal")) {
                for (int line=0;line<damage.size();line++) damage.set(line,options.damage(damage.get(line),max,m.getHp(),line));
            }
            batch.put(m.getObjectId(), new AttackTarget((short) 0, damage));
            if (batch.size() == 15) { applyBatch(s, attack, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) applyBatch(s, attack, batch);
        int applied = (int) Math.min(100, s.hits - hitsBefore);
        s.lastFmaReason = victims.isEmpty() ? "No eligible extra monsters in map"
                : applied == 0 ? "Extra targets found, but no damage accepted" : "Damage applied";
        return applied;
    }
    private void applyBatch(Session s, AttackInfo attack, LinkedHashMap<Integer, AttackTarget> batch) {
        // v83's packed target nibble is <=15; normal kill pipeline retains EXP, ownership and drops.
        int lines = batch.values().iterator().next().damageLines().size();
        s.map.broadcastMessage(TrainerFmaPolicy.packet(s.actor, attack, batch));
        for (var t : batch.entrySet()) {
            Monster m = s.map.getMonsterByOid(t.getKey()); if (m == null || !eligible(m)) continue;
            int damage = (int)Math.min(Integer.MAX_VALUE, t.getValue().damageLines().stream().mapToLong(Integer::longValue).sum());
            m.aggroMonsterDamage(s.actor, damage);
            s.actor.sendPacket(PacketCreator.damageMonster(m.getObjectId(), damage));
            if (s.map.damageMonster(s.actor, m, damage)) s.hits++;
        }
    }
}

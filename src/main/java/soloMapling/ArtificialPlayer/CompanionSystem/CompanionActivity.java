package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import java.util.*;

/** Thirty-second, map/party-scoped evidence. No idle, overheal or animation-only participation. */
public final class CompanionActivity {
    private record Event(long at, long damage, boolean support) {}
    private record Healing(long at, int targetId, double fraction) {}
    private static final class Window {
        final java.lang.ref.WeakReference<Object> map, party;
        final Deque<Event> events = new ArrayDeque<>();
        final Deque<Healing> healing = new ArrayDeque<>();
        Window(Character c) { map = new java.lang.ref.WeakReference<>(c.getMap()); party = new java.lang.ref.WeakReference<>(c.getParty()); }
    }
    private static final Map<Character, Window> windows = new WeakHashMap<>();
    private record Granted(java.lang.ref.WeakReference<Character> source,
                           java.lang.ref.WeakReference<Object> mapRef, java.lang.ref.WeakReference<Object> partyRef,
                           int skillId, client.BuffStat stat, long grantedAt, long expires) {
        Granted(Character caster, Object map, Object party, int skillId, client.BuffStat stat, long grantedAt, long expires) {
            this(new java.lang.ref.WeakReference<>(caster),new java.lang.ref.WeakReference<>(map),
                    new java.lang.ref.WeakReference<>(party),skillId,stat,grantedAt,expires);
        }
        Character caster() { return source.get(); }
        Object map() { return mapRef.get(); }
        Object party() { return partyRef.get(); }
    }
    private static final Map<Character, List<Granted>> buffs = new WeakHashMap<>();
    private CompanionActivity() {}
    private static Window window(Character c) {
        Window w = windows.get(c);
        if (w == null || w.map.get() != c.getMap() || w.party.get() != c.getParty()) {
            w = new Window(c); windows.put(c, w);
        }
        long cutoff = System.currentTimeMillis() - 30000;
        while (!w.events.isEmpty() && w.events.getFirst().at() < cutoff) w.events.removeFirst();
        while (!w.healing.isEmpty() && w.healing.getFirst().at() < cutoff) w.healing.removeFirst();
        return w;
    }
    public static synchronized void damage(Character c, int actual) {
        if (c == null || c.getParty() == null || actual <= 0) return;
        Window w = window(c);
        w.events.addLast(new Event(System.currentTimeMillis(), actual, false));
        while (w.events.size() > 512) w.events.removeFirst();
        usefulBuffs(c, false);
    }
    public static synchronized void healing(Character caster, Character target, int effective) {
        if (caster == null || target == null || caster == target || effective <= 0 || !caster.isAlive() || !target.isAlive()
                || caster.getParty() == null || caster.getParty() != target.getParty()
                || caster.getMap() != target.getMap() || !engaged(target)) return;
        Window w = window(caster);
        w.healing.addLast(new Healing(System.currentTimeMillis(), target.getId(),
                effective / (double)Math.max(1, target.getCurrentMaxHp())));
        while (w.healing.size() > 512) w.healing.removeFirst();
    }
    public static synchronized boolean engaged(Character c) {
        return c != null && c.isAlive() && !window(c).events.isEmpty();
    }
    public static synchronized boolean active(Character c, Collection<Character> members, long immediateDamage) {
        if (c == null || !c.isAlive()) return false;
        Window own = window(c);
        if (immediateDamage > 0) return true; // Valid short-kill contribution, never a waiting period.
        if (own.events.stream().anyMatch(Event::support)) return true;
        if (meaningfulHealing(own)) return true;
        long damage = own.events.stream().mapToLong(Event::damage).sum();
        long partyDamage = members.stream().map(CompanionActivity::window)
                .flatMap(w -> w.events.stream()).mapToLong(Event::damage).sum();
        return damage > 0 && (damage >= Math.max(1, partyDamage / 100)
                || own.events.stream().filter(e -> e.damage() > 0).count() >= 3);
    }
    public static synchronized void granted(Character caster, Character target, int skillId,
                                             client.BuffStat stat, long expires) {
        if (caster == target || caster.getParty() == null) return;
        var grants = buffs.computeIfAbsent(target, ignored -> new ArrayList<>());
        grants.removeIf(g -> g.stat() == stat || g.expires() < System.currentTimeMillis());
        grants.add(new Granted(caster, caster.getMap(), caster.getParty(), skillId, stat, System.currentTimeMillis(), expires));
    }
    private static boolean meaningfulHealing(Window w) {
        Map<Integer, Double> fractions = new HashMap<>();
        for (Healing h : w.healing) fractions.merge(h.targetId(), h.fraction(), Double::sum);
        return fractions.values().stream().anyMatch(f -> f >= .05);
    }
    private static boolean usefulCaster(Granted grant, Character target, long now) {
        Character caster = grant.caster();
        if (caster == null) return false;
        if (caster.getPosition() == null || target.getPosition() == null
                || Math.abs(caster.getPosition().x - target.getPosition().x) > 700
                || Math.abs(caster.getPosition().y - target.getPosition().y) > 350) return false;
        var task = CompanionTaskService.shared().task(caster.getId()).orElse(null);
        if (task != null) return (task.state() == CompanionTaskService.State.ENGAGE
                || task.state() == CompanionTaskService.State.SUPPORT)
                && caster.getParty() != null && caster.getParty().getId() == task.party().partyId();
        Window w = window(caster);
        // Old support events cannot renew themselves forever through an idle human's long buff.
        return now - grant.grantedAt() <= 30000 || w.events.stream().anyMatch(e -> e.damage() > 0)
                || meaningfulHealing(w);
    }
    private static void usefulBuffs(Character target, boolean holySymbol) {
        long now = System.currentTimeMillis();
        var grants = buffs.getOrDefault(target, List.of());
        for (Granted grant : grants) {
            Character caster = grant.caster();
            if (caster == null) continue;
            if ((grant.stat() == client.BuffStat.HOLY_SYMBOL) != holySymbol || grant.expires() <= now
                    || grant.map() != target.getMap() || grant.party() != target.getParty()
                    || caster.getMap() != target.getMap() || caster.getParty() != target.getParty()
                    || !caster.isAlive() || target.getBuffSource(grant.stat()) != grant.skillId()
                    || !usefulCaster(grant, target, now)) continue;
            var events = window(caster).events;
            events.addLast(new Event(now, 0, true));
            while (events.size() > 512) events.removeFirst();
        }
    }
    /** Called after an awarded kill: HS can establish activity only for a subsequent kill snapshot. */
    public static synchronized void awardedKill(Collection<Character> recipients, boolean sharers) {
        if (sharers) for (Character recipient : recipients) if (engaged(recipient)) usefulBuffs(recipient, true);
    }
    public static synchronized void clear(Character c) {
        windows.remove(c); buffs.remove(c);
        buffs.values().forEach(list -> list.removeIf(grant -> grant.caster() == c));
    }
}

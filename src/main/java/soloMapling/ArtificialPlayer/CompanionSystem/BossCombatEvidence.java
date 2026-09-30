package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import server.life.Monster;
import java.awt.Point;
import java.util.*;

/** Bounded evidence windows, distinguishing aim, resources, damage progress and reach failures. */
public final class BossCombatEvidence {
    private static final class Window {
        long start, warning, unreachableAt, lastProgress;
        int attempts, misses, potions;
        long actualDamage, incoming;
        Point position;
    }
    private static final Map<Character,Window> windows = new WeakHashMap<>();
    private BossCombatEvidence() {}
    private static synchronized Window window(Character c) {
        return windows.computeIfAbsent(c, x -> { Window w = new Window(); w.start = System.currentTimeMillis(); return w; });
    }
    public static void accuracy(Character c, boolean missed) {
        if (!BossRuntime.get().active(c)) return;
        Window w = window(c); synchronized (w) { w.attempts++; if (missed) w.misses++; }
    }
    public static void damage(Character c, int actual) {
        if (!BossRuntime.get().active(c)) return;
        Window w = window(c); synchronized (w) { w.actualDamage += Math.max(0,actual); }
    }
    public static void incoming(Character c, int actual) {
        if (!BossRuntime.get().active(c)) return;
        Window w = window(c); synchronized (w) { w.incoming += Math.max(0,actual); }
    }
    public static void potion(Character c) {
        if (!BossRuntime.get().active(c)) return;
        Window w = window(c); synchronized (w) { w.potions++; }
    }
    public static void reach(Character c, boolean inRange) {
        if (!BossRuntime.get().active(c)) return;
        Window w = window(c); long now = System.currentTimeMillis();
        synchronized (w) {
            if (inRange) { w.unreachableAt = 0; w.position = null; return; }
            if (w.unreachableAt == 0) w.unreachableAt = now;
            if (w.position == null || w.position.distanceSq(c.getPosition()) > 64) {
                w.position = new Point(c.getPosition()); w.lastProgress = now;
            }
            if (now - w.lastProgress >= 15_000) report(c,w,"I can't reach it from this position.",now);
            if (now - w.lastProgress >= 30_000) BossRuntime.get().withdrawal(c,BossObjective.Outcome.RETREATED,"No legal engagement position was reachable.");
        }
    }
    public static void review(Character c, Monster target) {
        if (!BossRuntime.get().active(c)) return;
        Window w = window(c); long now = System.currentTimeMillis();
        synchronized (w) {
            if (now - w.start < 10_000) return;
            if (w.attempts >= 10 && w.misses > w.attempts * .6) report(c,w,"I can't hit it reliably.",now);
            else if (w.potions >= 5) report(c,w,"I'm burning through my pots. We should reconsider this fight.",now);
            else if (w.attempts >= 10 && w.actualDamage > 0 && target != null && target.getHp() > 0
                    && w.actualDamage * 180 < target.getHp()) report(c,w,"We need more damage for this boss.",now);
            if (now - w.start >= 30_000) { w.start = now; w.attempts = w.misses = w.potions = 0; w.actualDamage = w.incoming = 0; }
        }
    }
    private static void report(Character c, Window w, String text, long now) {
        if (now - w.warning < 60_000) return;
        w.warning = now; soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat(c,text);
    }
    public static synchronized void clear(Character c) { windows.remove(c); }
}

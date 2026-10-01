package soloMapling.ArtificialPlayer.HybridPilot;

import client.Character;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionBuild;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/** Process-wide cap, manual admission only, and removal of this cohort only. No persisted enable flag. */
public final class HybridPilotService {
    public static final int LIMIT = 3;
    private static final HybridPilotService INSTANCE = new HybridPilotService(HybridPilotService::create);
    interface Factory { HybridPilotBot create(Character owner, String name); }
    private final Factory factory;
    private final List<HybridPilotBot> bots = new java.util.concurrent.CopyOnWriteArrayList<>();
    private long serial;

    HybridPilotService(Factory factory) { this.factory = factory; }
    public static HybridPilotService get() { return INSTANCE; }
    public static boolean isPilot(Character bot) {
        return bot != null && CharacterStorage.getBotById(bot.getId()) instanceof HybridPilotBot;
    }

    private static HybridPilotBot create(Character owner, String name) {
        MapleMap map = owner.getMap();
        if (map == null || map.getEventInstance() != null || owner.getEventInstance() != null || owner.getPartyQuest() != null)
            throw new IllegalArgumentException("Spawn the pilot on an ordinary map outside a party quest/event.");
        Character body = BotGeneration.createHybridPilot(new Point(owner.getPosition()), map, name);
        try {
            body.setGMLevel(0);
            CompanionBuild.initializeAmbient(body);
            HybridPilotBot bot = new HybridPilotBot(body);
            CharacterStorage.addActiveBot(body.getId(), bot);
            // Full HP only at initial creation, never on a mode change, relocation or wake.
            body.updateHp(body.getCurrentMaxHp());
            return bot;
        } catch (RuntimeException failure) {
            try { BotGeneration.removeBotFromServer(body); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public synchronized List<String> spawn(Character owner, int count) {
        bots.removeIf(HybridPilotBot::removed);
        if (count < 1 || count > LIMIT || bots.size() + count > LIMIT)
            throw new IllegalArgumentException("Pilot limit is three total; use !hybrid status or !hybrid off.");
        List<HybridPilotBot> added = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                HybridPilotBot bot = factory.create(owner, "Hybrid" + (++serial));
                bots.add(bot);
                added.add(bot);
                bot.startScheduledTask();
            }
        } catch (RuntimeException failure) {
            for (HybridPilotBot bot : added) {
                try { bot.remove(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            bots.removeIf(HybridPilotBot::removed);
            throw failure;
        }
        return added.stream().map(HybridPilotBot::status).toList();
    }

    public synchronized List<String> status() {
        bots.removeIf(HybridPilotBot::removed);
        List<String> lines = new ArrayList<>();
        lines.add("Hybrid pilot: " + bots.size() + "/" + LIMIT + "; manual spawns only; 30 minute lifetime; no trading.");
        bots.forEach(b -> lines.add(b.status()));
        return lines;
    }

    /** Explicit GM relocation, not automatic offscreen follow/travel. Character/HP/controller are preserved. */
    public synchronized int here(Character owner) {
        int moved = 0;
        for (HybridPilotBot bot : bots) if (bot.relocate(owner)) moved++;
        return moved;
    }

    public synchronized int off() {
        int count = bots.size();
        RuntimeException failure = null;
        for (HybridPilotBot bot : bots) {
            try { bot.remove(); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        bots.removeIf(HybridPilotBot::removed);
        if (failure != null) throw new IllegalStateException("Pilot stopped; some body cleanup failed. Check logs and retry !hybrid off.", failure);
        return count;
    }

    public boolean chat(Character sender, String text) {
        for (HybridPilotBot bot : bots) if (bot.chat(sender, text)) return true;
        return false;
    }
}

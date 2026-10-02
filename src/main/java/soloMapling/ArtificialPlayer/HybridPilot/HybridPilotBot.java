package soloMapling.ArtificialPlayer.HybridPilot;

import client.BotClient;
import client.Character;
import client.Disease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.server.BotTickService;

import java.util.Locale;
import java.util.function.LongSupplier;

/** An opt-in, stationary social/combat canary. Never wraps a self-scheduling legacy activity. */
public final class HybridPilotBot extends BotSM {
    private static final Logger log = LoggerFactory.getLogger(HybridPilotBot.class);
    public static final long PERIOD_MS = 500;
    public static final long LIFETIME_MS = 30 * 60_000;
    public enum Mode { DORMANT, SOCIAL, COMBAT, DEAD, BLOCKED, FAULTED, STOPPED }

    interface Effects {
        boolean contact(Character bot, MapleMap map);
        boolean attack(Character bot, MapleMap map);
        void speak(Character bot, String message);
        void remove(Character bot);
    }

    private record Reply(Character sender, MapleMap map, String text, long expires) {}
    private final LongSupplier clock;
    private final Effects effects;
    private final long expires;
    private Mode mode = Mode.DORMANT;
    private boolean closed, removed;
    private Reply reply;
    private long nextAttack, nextContact, nextSpeech;
    private long ticks, attacks, contacts, speeches, transitions, maxTickNanos;
    private String failure = "";

    public HybridPilotBot(Character bot) {
        this(bot, System::currentTimeMillis, new HybridPilotEffects());
    }

    HybridPilotBot(Character bot, LongSupplier clock, Effects effects) {
        super(bot);
        this.clock = clock;
        this.effects = effects;
        this.expires = clock.getAsLong() + LIFETIME_MS;
        botType = "HybridPilot";
    }

    /** Identity of the map object isolates worlds, channels and event instances with the same map ID. */
    public static boolean observed(MapleMap map) {
        return map != null && map.getCharacters().stream().anyMatch(c -> c.getMap() == map
                && c.getClient() != null && !(c.getClient() instanceof BotClient));
    }

    static boolean usable(Character bot, MapleMap map) {
        return bot.getMap() == map && observed(map) && map.getEventInstance() == null && bot.isAlive()
                && bot.getParty() == null && bot.getEventInstance() == null && bot.getPartyQuest() == null
                && bot.getTrade() == null && bot.getPlayerShop() == null && bot.getShop() == null
                && bot.getMiniGame() == null;
    }

    @Override public synchronized boolean checkMainPlayersOnMap() { return observed(getChr().getMap()); }

    @Override public synchronized void startScheduledTask(long delay) {
        if (closed) return; // retired actors cannot be restarted by late legacy callbacks
        setRunning(true);
        state = BotState.RUNNING;
        BotTickService.register(getChr().getId(), this::updateState, Math.max(0, delay), PERIOD_MS);
    }

    @Override public synchronized void startScheduledTask() { startScheduledTask(0); }
    @Override public synchronized void nudgeSoon(long delay) { /* The bounded 500ms cadence already wakes promptly. */ }

    @Override public synchronized void stopScheduledTask() {
        closed = true;
        setRunning(false);
        reply = null;
        state = BotState.FINISHED;
        transition(Mode.STOPPED);
        if (CharacterStorage.getBotById(getChr().getId()) == this) BotTickService.unregister(getChr().getId());
    }

    /** Stop drains the actor monitor before removing its body; off returning means no pilot action is in flight. */
    public synchronized void remove() {
        if (removed) return;
        stopScheduledTask();
        effects.remove(getChr());
        removed = true; // failed cleanup stays tracked and may be retried with off
    }

    synchronized boolean relocate(Character owner) {
        if (closed || owner.getMap() == null || owner.getMap().getEventInstance() != null
                || owner.getEventInstance() != null || owner.getPartyQuest() != null
                || owner.getMap().getChannelServer() != getChr().getMap().getChannelServer()) return false;
        reply = null;
        getChr().changeMap(owner.getMap(), new java.awt.Point(owner.getPosition()));
        return true;
    }

    @Override public synchronized void updateState() {
        if (closed || !getRunning()) return;
        long started = System.nanoTime();
        try {
            if (CharacterStorage.getBotById(getChr().getId()) != this) {
                stopScheduledTask();
                return;
            }
            long now = clock.getAsLong();
            if (now >= expires) { remove(); return; }
            ticks++;
            Character bot = getChr();
            MapleMap map = bot.getMap();
            if (!observed(map)) {
                reply = null; // no delayed conversation/catch-up after an empty map
                transition(Mode.DORMANT);
                return;
            }
            if (!bot.isAlive()) { reply = null; transition(Mode.DEAD); return; }
            if (!usable(bot, map)) { reply = null; transition(Mode.BLOCKED); return; }

            // Vulnerability is independent of the social/combat decision and checked first.
            if (now >= nextContact) {
                if (effects.contact(bot, map)) { contacts++; nextContact = now + 1500; }
            }
            if (!usable(bot, map)) {
                reply = null;
                transition(bot.isAlive() ? Mode.BLOCKED : Mode.DEAD);
                return;
            }
            boolean disabled = bot.hasDisease(Disease.STUN) || bot.hasDisease(Disease.SEDUCE)
                    || bot.hasDisease(Disease.SEAL);
            if (disabled) transition(Mode.BLOCKED);
            else if (now >= nextAttack) {
                boolean attacked = effects.attack(bot, map);
                if (attacked) { attacks++; nextAttack = now + 1500; }
                transition(attacked ? Mode.COMBAT : Mode.SOCIAL);
            }
            if (reply != null && now >= nextSpeech) {
                Reply pending = reply;
                reply = null;
                if (now < pending.expires() && pending.map() == map && pending.sender().getMap() == map
                        && usable(bot, map)) {
                    effects.speak(bot, pending.text());
                    speeches++;
                    nextSpeech = now + 3000;
                }
            }
        } catch (Exception e) {
            stopScheduledTask();
            transition(Mode.FAULTED);
            failure = e.getClass().getSimpleName();
            log.error("Hybrid pilot {} stopped after an error; use !hybrid off to remove it", getChr().getId(), e);
        } finally { maxTickNanos = Math.max(maxTickNanos, System.nanoTime() - started); }
    }

    /** One expiring mailbox slot; a chat flood cannot create tasks or an unbounded backlog. */
    public synchronized boolean chat(Character sender, String text) {
        if (closed || !getRunning() || sender.getMap() != getChr().getMap()) return false;
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (!addressed(getChr().getName(), text)) return false;
        long now = clock.getAsLong();
        if (reply != null || now < nextSpeech || !getChr().isAlive()) return true;
        String answer = normalized.contains("buy") || normalized.contains("sell") || normalized.contains("trade")
                ? "Trading isn't part of my pilot yet. I can chat and fight nearby monsters."
                : normalized.contains("status") || normalized.contains("mode")
                ? "My mode is " + mode + ". HP " + getChr().getHp() + "/" + getChr().getCurrentMaxHp() + "."
                : "Hey " + sender.getName() + "! I'm watching for nearby monsters while we talk.";
        reply = new Reply(sender, sender.getMap(), answer, now + 3000);
        return true;
    }

    static boolean addressed(String botName, String text) {
        String name = botName.toLowerCase(Locale.ROOT);
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.equals(name) || normalized.startsWith(name + " ") || normalized.startsWith(name + ":");
    }

    private void transition(Mode next) { if (mode != next) { mode = next; transitions++; } }
    synchronized Mode mode() { return mode; }
    synchronized boolean removed() { return removed; }
    public synchronized String status() {
        return getChr().getName() + " id=" + getChr().getId() + " mode=" + mode + " map=" + getChr().getMapId()
                + " hp=" + getChr().getHp() + "/" + getChr().getCurrentMaxHp() + " ticks=" + ticks
                + " attacks=" + attacks + " contacts=" + contacts + " replies=" + speeches
                + " transitions=" + transitions + " maxTickMs=" + (maxTickNanos / 1_000_000)
                + (failure.isEmpty() ? "" : " error=" + failure);
    }
}

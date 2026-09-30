package server.trainer;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;
import server.StatEffect;
import server.maps.FieldLimit;
import tools.PacketCreator;
import java.util.Map;
import java.util.Set;

/** Bounded ordinary potion use. Called only while the owning trainer lease is valid. */
final class TrainerAutoPotion {
    record Options(boolean hp, boolean mp, int hpThreshold, int mpThreshold,
                   int hpItem, int mpItem, int reserve, int interval) {
        Options {
            if (hpThreshold < 1 || hpThreshold > 99 || mpThreshold < 1 || mpThreshold > 99
                    || reserve < 0 || reserve > 1000 || interval < 500 || interval > 10_000)
                throw new IllegalArgumentException("Potion thresholds 1..99, reserve 0..1000, interval 500..10000 ms.");
            if (!Set.of(2000000, 2000001, 2000002, 2000004, 2000005).contains(hpItem)
                    || !Set.of(2000003, 2000006, 2000004, 2000005).contains(mpItem))
                throw new IllegalArgumentException("Select a supported ordinary HP/MP potion.");
        }
        static Options defaults() { return new Options(false, false, 50, 30, 2000002, 2000006, 5, 1000); }
    }

    private Options options = Options.defaults();
    private final java.util.function.IntFunction<StatEffect> effects;
    private long nextUse, consumed;
    private String detail = "Auto potion OFF";
    TrainerAutoPotion() { this(id -> ItemInformationProvider.getInstance().getItemEffect(id)); }
    TrainerAutoPotion(java.util.function.IntFunction<StatEffect> effects) { this.effects = effects; }
    void configure(Options desired) { options = desired; detail = "Auto potion configured; HP has priority"; }
    void off() {
        options = new Options(false, false, options.hpThreshold, options.mpThreshold,
                options.hpItem, options.mpItem, options.reserve, options.interval);
        detail = "Auto potion OFF; rearm explicitly after a map change";
    }
    void status(Map<String, String> result) {
        result.put("autoPotionReady", "1");
        result.put("autoHp", options.hp ? "1" : "0"); result.put("autoMp", options.mp ? "1" : "0");
        result.put("autoHpThreshold", Integer.toString(options.hpThreshold)); result.put("autoMpThreshold", Integer.toString(options.mpThreshold));
        result.put("autoHpItem", Integer.toString(options.hpItem)); result.put("autoMpItem", Integer.toString(options.mpItem));
        result.put("autoReserve", Integer.toString(options.reserve)); result.put("autoInterval", Integer.toString(options.interval));
        result.put("autoConsumed", Long.toString(consumed)); result.put("autoDetail", detail);
    }
    static boolean below(int current, int maximum, int threshold) {
        return maximum > 0 && current < maximum && (long) current * 100 <= (long) maximum * threshold;
    }
    void tick(Character actor, long now) {
        if ((!options.hp && !options.mp) || now < nextUse) return;
        if (!actor.isAlive() || !actor.isLoggedinWorld() || actor.isChangingMaps()
                || actor.getClient().isInTransition() || actor.getTrade() != null
                || actor.getShop() != null || actor.getClient().getCM() != null) {
            detail = "Auto potion paused during death, loading, trade or dialogue"; return;
        }
        if (actor.getMap() == null || FieldLimit.CANNOTUSEPOTION.check(actor.getMap().getFieldLimit())) {
            detail = "Auto potion paused: this map disallows potions"; return;
        }
        boolean hp = options.hp && below(actor.getHp(), actor.getCurrentMaxHp(), options.hpThreshold);
        boolean mp = options.mp && below(actor.getMp(), actor.getCurrentMaxMp(), options.mpThreshold);
        if (!hp && !mp) { detail = "Auto potion waiting for threshold"; return; }
        nextUse = now + options.interval; // Configuration changes never reset a spent action budget.
        int itemId = hp ? options.hpItem : options.mpItem;
        synchronized (actor) {
            var inventory = actor.getInventory(InventoryType.USE);
            inventory.lockInventory();
            try {
                var item = inventory.findById(itemId);
                if (item == null || inventory.countById(itemId) <= options.reserve) {
                    options = new Options(hp ? false : options.hp, hp ? options.mp : false,
                            options.hpThreshold, options.mpThreshold, options.hpItem,
                            options.mpItem, options.reserve, options.interval);
                    detail = (hp ? "HP" : "MP") + " auto potion stopped at reserve/empty; rearm after restocking";
                    return;
                }
                var effect = effects.apply(itemId);
                if (effect == null || (hp ? effect.getHp() <= 0 && effect.getHpRate() <= 0
                        : effect.getMp() <= 0 && effect.getMpRate() <= 0)) {
                    off(); detail = "Auto potion stopped: invalid potion effect"; return;
                }
                // Same consume-then-apply ordering as the ordinary UseItemHandler. Exactly one
                // actual unit per cadence; no fabricated healing or compulsory multi-pot loop.
                InventoryManipulator.removeFromSlot(actor.getClient(), InventoryType.USE,
                        item.getPosition(), (short) 1, false);
                consumed++;
                boolean applied = effect.applyTo(actor);
                detail = applied ? "Consumed " + itemId + "; total " + consumed : "Potion consumed; ordinary effect rejected";
                actor.sendPacket(PacketCreator.enableActions());
            } finally { inventory.unlockInventory(); }
        }
    }
}

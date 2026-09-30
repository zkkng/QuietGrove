package server.pccafe;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/** Owned by a Character; callers hold that character's monitor, also used by saveCharToDB. */
public final class PcCafeState {
    private LocalDate week = LocalDate.MIN;
    private int balance, earned, gauge;
    private boolean welcomed;
    private final Map<Integer, Integer> purchases = new TreeMap<>();

    public void refresh(Instant now, PcCafeConfig config) {
        LocalDate current = now.atZone(config.resetZone()).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // A backwards clock must not clear limits or award a second weekly allowance.
        if (current.isAfter(week)) { week = current; earned = 0; purchases.clear(); }
    }
    public int welcome(PcCafeConfig config) {
        if (welcomed) return 0;
        welcomed = true;
        int amount = Math.min(config.welcomeCoins(), Math.max(0, config.maxBalance() - balance));
        balance += amount;
        return amount;
    }
    public int available(PcCafeConfig config) {
        return Math.max(0, Math.min(config.weeklyCap() - earned, config.maxBalance() - balance));
    }
    public boolean kill(PcCafeConfig config) {
        if (available(config) == 0) return false;
        if (++gauge >= config.killsPerCoin()) { gauge = 0; credit(1); return true; }
        return false;
    }
    public void credit(int amount) { if (amount < 0) throw new IllegalArgumentException("Negative cafe credit"); balance = Math.addExact(balance, amount); earned = Math.addExact(earned, amount); }
    public boolean purchase(int item, int price, int limit, BooleanSupplier give) {
        if (price <= 0 || balance < price || (limit > 0 && purchases.getOrDefault(item, 0) >= limit)) return false;
        // Give first under the same character/inventory lock. A failed grant never consumes coins or limits.
        if (!give.getAsBoolean()) return false;
        balance -= price;
        if (limit > 0) purchases.merge(item, 1, Integer::sum);
        return true;
    }
    public int balance() { return balance; }
    public int earned() { return earned; }
    public int gauge() { return gauge; }
    public int purchased(int item) { return purchases.getOrDefault(item, 0); }
    public String encode() {
        StringBuilder s = new StringBuilder("1|" + week + "|" + balance + "|" + earned + "|" + gauge + "|" + (welcomed ? 1 : 0) + "|");
        purchases.forEach((item, count) -> s.append(item).append(':').append(count).append(','));
        return s.toString();
    }
    public static PcCafeState decode(String encoded) {
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 7 || !parts[0].equals("1")) throw new IllegalArgumentException("Unknown PC Cafe save format");
        PcCafeState state = new PcCafeState();
        state.week = LocalDate.parse(parts[1]);
        state.balance = nonnegative(parts[2]); state.earned = nonnegative(parts[3]); state.gauge = nonnegative(parts[4]);
        if (!parts[5].equals("0") && !parts[5].equals("1")) throw new IllegalArgumentException("Invalid welcome flag");
        state.welcomed = parts[5].equals("1");
        if (!parts[6].isEmpty()) for (String entry : parts[6].split(",")) {
            String[] pair = entry.split(":", -1);
            if (pair.length != 2 || state.purchases.put(nonnegative(pair[0]), nonnegative(pair[1])) != null)
                throw new IllegalArgumentException("Invalid PC Cafe purchase record");
        }
        return state;
    }
    private static int nonnegative(String text) {
        int value = Integer.parseInt(text);
        if (value < 0) throw new IllegalArgumentException("Negative PC Cafe value");
        return value;
    }
}

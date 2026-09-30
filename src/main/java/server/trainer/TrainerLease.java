package server.trainer;

/** Pure bounded state machine. All calls are serialized by the owning trainer session. */
public final class TrainerLease {
    public static final long LEASE_MS = 5_000;
    public static final long ARM_MS = 750;
    public static final int MIN_INTERVAL_MS = 150;
    private long expires, armedUntil, nextAttack, nextClientAttack, nextVac, nextItemVac, nextRegen, requestWindow;
    private int requests;
    private boolean vac, itemVac, mesoVac, lootOnKey, fma, rapid, hpGod, hpRegen, mpRegen;
    private int lootRadius, lootBatch = 8;
    private int interval = 300;
    private int fmaDamage = 1;
    private boolean fmaOneHit;

    public TrainerLease(long now) { renew(now); }
    public void renew(long now) { expires = now + LEASE_MS; }
    public long expiresAt() { return expires; }
    public boolean alive(long now) { return now < expires; }
    public boolean allowRequest(long now) {
        if (now - requestWindow >= 1_000) { requestWindow = now; requests = 0; }
        return ++requests <= 8;
    }
    public void configure(boolean vac, boolean itemVac, boolean fma, boolean rapid, int interval, long now) {
        configure(vac, itemVac, itemVac, false, 0, 8, fma, rapid, interval, now);
    }
    public void configure(boolean vac, boolean itemVac, boolean mesoVac, boolean lootOnKey,
                          int lootRadius, int lootBatch, boolean fma, boolean rapid, int interval, long now) {
        configure(vac, itemVac, mesoVac, lootOnKey, lootRadius, lootBatch, fma, rapid,
                false, false, false, interval, now);
    }
    public void configure(boolean vac, boolean itemVac, boolean mesoVac, boolean lootOnKey,
                          int lootRadius, int lootBatch, boolean fma, boolean rapid,
                          boolean hpGod, boolean hpRegen, boolean mpRegen, int interval, long now) {
        if (interval < MIN_INTERVAL_MS || interval > 1_000) throw new IllegalArgumentException("Cadence must be 150..1000 ms");
        if (lootRadius < 0 || lootRadius > 2000 || lootBatch < 1 || lootBatch > 25)
            throw new IllegalArgumentException("Loot radius must be 0..2000 and batch 1..25.");
        this.vac = vac; this.itemVac = itemVac; this.mesoVac = mesoVac; this.lootOnKey = lootOnKey;
        this.lootRadius = lootRadius; this.lootBatch = lootBatch;
        this.fma = fma; this.rapid = rapid; this.hpGod = hpGod; this.hpRegen = hpRegen; this.mpRegen = mpRegen; this.interval = interval;
        armedUntil = 0; nextAttack = now + interval; renew(now);
    }
    public void fmaPower(int multiplier, boolean oneHit) {
        if (multiplier < 1 || multiplier > 100) throw new IllegalArgumentException("FMA multiplier must be 1..100.");
        fmaDamage = multiplier; fmaOneHit = oneHit;
    }
    public void off() { vac = false; itemVac = false; mesoVac = false; lootOnKey = false; fma = false; rapid = false; hpGod = false; hpRegen = false; mpRegen = false; fmaDamage = 1; fmaOneHit = false; armedUntil = 0; }
    public void disableFma() { fma = false; }
    public void disableVac() { vac = false; }
    public void disableItemVac() { itemVac = false; mesoVac = false; lootOnKey = false; }
    public void disableRegen() { hpRegen = false; mpRegen = false; }
    public void revoke() { off(); expires = 0; }
    public boolean vac() { return vac; }
    public boolean itemVac() { return itemVac; }
    public boolean mesoVac() { return mesoVac; }
    public boolean lootOnKey() { return lootOnKey; }
    public int lootRadius() { return lootRadius; }
    public int lootBatch() { return lootBatch; }
    public boolean fma() { return fma; }
    public int fmaDamage() { return fmaDamage; }
    public boolean fmaOneHit() { return fmaOneHit; }
    public boolean rapid() { return rapid; }
    /** Shared budget for genuine client melee/ranged/magic casts; normal play is unchanged. */
    public boolean allowClientAttack(long now) {
        if (!alive(now) || !rapid) return true;
        if (now < nextClientAttack) return false;
        nextClientAttack = now + interval;
        return true;
    }
    public boolean hpGod() { return hpGod; }
    public boolean hpRegen() { return hpRegen; }
    public boolean mpRegen() { return mpRegen; }
    public int interval() { return interval; }
    public void acceptedAttack(long now) { armedUntil = now + ARM_MS; nextAttack = now + interval; }
    public void mapChanged() { armedUntil = 0; }
    public boolean takeRapid(long now) {
        if (!alive(now) || !rapid || now >= armedUntil || now < nextAttack) return false;
        nextAttack = now + interval; return true; // no catch-up bursts after stalls
    }
    public boolean takeVac(long now) {
        if (!alive(now) || !vac || now < nextVac) return false;
        nextVac = now + 500; return true;
    }
    public boolean takeItemVac(long now) {
        if (!alive(now) || (!itemVac && !mesoVac) || lootOnKey || now < nextItemVac) return false;
        nextItemVac = now + 500; return true;
    }
    public boolean takeRegen(long now) { return takeRegen(now, 1000); }
    public boolean takeRegen(long now, int cadence) {
        if (cadence < 250 || cadence > 5000) throw new IllegalArgumentException("Regen cadence");
        if (!alive(now) || (!hpRegen && !mpRegen) || now < nextRegen) return false;
        nextRegen = now + cadence; return true;
    }
}

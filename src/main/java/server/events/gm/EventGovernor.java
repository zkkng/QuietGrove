package server.events.gm;

/** Admission/arrival/spectator load shedding with hysteresis. Active rules and scores are never reduced. */
public final class EventGovernor {
    public enum Mode { NORMAL, CLOSE_ADMISSION, SLOW_ARRIVALS, QUIET_SPECTATORS, STOP }
    public record Sample(long timeMs,double actionP99Ms,double outboundAgeMs,double cpuPercent,double heapPercent,boolean queueGrowing) {}
    private Mode mode=Mode.NORMAL;
    private long overloadedAt=-1, healthyAt=-1;
    public synchronized Mode update(Sample sample) {
        if(mode==Mode.STOP) return mode;
        boolean bad=sample.actionP99Ms()>200 || sample.outboundAgeMs()>200 || sample.cpuPercent()>80
                || sample.heapPercent()>75 || sample.queueGrowing();
        boolean hard=sample.actionP99Ms()>1000 || sample.outboundAgeMs()>2000 || sample.heapPercent()>90;
        if(bad) {
            healthyAt=-1; if(overloadedAt<0) overloadedAt=sample.timeMs();
            long duration=sample.timeMs()-overloadedAt;
            mode=hard && duration>=10000?Mode.STOP:duration>=5000?Mode.QUIET_SPECTATORS:duration>=2000?Mode.SLOW_ARRIVALS:Mode.CLOSE_ADMISSION;
        } else {
            overloadedAt=-1; if(healthyAt<0) healthyAt=sample.timeMs();
            if(sample.timeMs()-healthyAt>=30000 && mode!=Mode.STOP) {
                mode=Mode.values()[Math.max(0,mode.ordinal()-1)]; healthyAt=sample.timeMs();
            }
        }
        return mode;
    }
    public synchronized Mode mode() { return mode; }
    public synchronized boolean admits() { return mode==Mode.NORMAL; }
    public synchronized boolean arrivals() { return mode.ordinal()<Mode.SLOW_ARRIVALS.ordinal(); }
    public synchronized boolean spectatorMotion() { return mode.ordinal()<Mode.QUIET_SPECTATORS.ordinal(); }
}

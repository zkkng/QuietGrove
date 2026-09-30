package server.trainer;

import constants.game.ExpTable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Session-local telemetry; no rewards, inventory, or character stats are written. */
final class TrainerSessionStats {
    private final long startedNanos=System.nanoTime(), initialExp, initialMesos;
    final AtomicLong finishingKills=new AtomicLong();
    TrainerSessionStats(int level,int exp,int mesos) { initialExp=totalExp(level,exp); initialMesos=mesos; }
    static long totalExp(int level,int exp) {
        long total=exp;
        for (int i=1;i<Math.min(level,201);i++) total+=ExpTable.getExpNeededForLevel(i);
        return total;
    }
    static long hourly(long amount,long elapsedMs) { return Math.round(amount*3_600_000.0/Math.max(1000,elapsedMs)); }
    void status(Map<String,String> r,int level,int exp,int mesos,long actions) {
        long elapsed=Math.max(0,(System.nanoTime()-startedNanos)/1_000_000), netExp=totalExp(level,exp)-initialExp;
        r.put("statsReady","1"); r.put("sessionMs",Long.toString(elapsed));
        r.put("finishingKills",Long.toString(finishingKills.get())); r.put("netExp",Long.toString(netExp));
        r.put("netMesos",Long.toString(mesos-initialMesos)); r.put("expPerHour",Long.toString(hourly(netExp,elapsed)));
        r.put("swingsPerMinute",Long.toString(hourly(actions,elapsed)/60));
    }
}

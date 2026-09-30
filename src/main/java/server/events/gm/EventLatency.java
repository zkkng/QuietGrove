package server.events.gm;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Fixed-size rolling minute histogram: old lag cannot permanently hold the governor closed. */
final class EventLatency {
    private static final double[] LIMITS={1,2,5,10,20,50,100,200,500,1000,2000,Double.POSITIVE_INFINITY};
    private final long[][] counts=new long[60][LIMITS.length];
    private final long[] seconds=new long[60];
    private final LongSupplier clock;
    EventLatency() {this(System::currentTimeMillis);}
    EventLatency(LongSupplier clock) {this.clock=clock;Arrays.fill(seconds,Long.MIN_VALUE);}
    synchronized void record(double ms) {
        long second=clock.getAsLong()/1000;int slot=Math.floorMod(second,60);
        if(seconds[slot]!=second) {Arrays.fill(counts[slot],0);seconds[slot]=second;}
        for(int bucket=0;bucket<LIMITS.length;bucket++) if(ms<=LIMITS[bucket]) {counts[slot][bucket]++;return;}
    }
    synchronized double percentile(double percentile) {
        long now=clock.getAsLong()/1000;long[] aggregate=new long[LIMITS.length];
        for(int slot=0;slot<60;slot++) if(seconds[slot]>now-60 && seconds[slot]<=now)
            for(int b=0;b<LIMITS.length;b++) aggregate[b]+=counts[slot][b];
        long total=Arrays.stream(aggregate).sum(),target=(long)Math.ceil(total*percentile),at=0;
        if(total==0) return 0;
        for(int b=0;b<aggregate.length;b++) if((at+=aggregate[b])>=target) return LIMITS[b];
        return 0;
    }
}

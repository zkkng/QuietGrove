package soloMapling.ArtificialPlayer.CompanionSystem;

import java.util.*;

/** Bounded measurements of executed server work; these do not estimate client FPS or network latency. */
public final class BossTelemetry {
    public enum Stage { LIFECYCLE, CONTROLLER, COMBAT, ROUTE }
    public record Timing(long count,double p50Ms,double p95Ms,double maxMs) {}
    public record Snapshot(long elapsedMs,Map<Stage,Timing> timings,long actionPackets,long actionBytes) {}
    private static final class Window { final long[] values=new long[1024]; long count,max; }
    private static final EnumMap<Stage,Window> windows=new EnumMap<>(Stage.class);
    private static long started=System.nanoTime(),packets,bytes;
    private BossTelemetry() {}
    public static synchronized void sample(Stage stage,long nanos) {
        Window w=windows.computeIfAbsent(stage,s->new Window());
        w.values[(int)(w.count++ % w.values.length)]=Math.max(0,nanos); w.max=Math.max(w.max,nanos);
    }
    public static synchronized void packet(int size) {packets++;bytes+=Math.max(0,size);}
    public static synchronized Snapshot snapshot() {
        Map<Stage,Timing> result=new EnumMap<>(Stage.class);
        windows.forEach((stage,w)->{
            long[] sorted=Arrays.copyOf(w.values,(int)Math.min(w.count,w.values.length));Arrays.sort(sorted);
            result.put(stage,new Timing(w.count,sorted[(sorted.length-1)/2]/1_000_000.0,
                    sorted[(int)Math.ceil(sorted.length*.95)-1]/1_000_000.0,w.max/1_000_000.0));
        });
        return new Snapshot((System.nanoTime()-started)/1_000_000,Map.copyOf(result),packets,bytes);
    }
    public static synchronized void reset() {windows.clear();packets=bytes=0;started=System.nanoTime();}
}

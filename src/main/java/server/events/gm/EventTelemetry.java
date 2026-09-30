package server.events.gm;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Bounded action latency histogram plus JVM resource samples; reports cannot substitute for client frame measurements. */
public final class EventTelemetry {
    private final EventLatency action=new EventLatency(),macro=new EventLatency(),movement=new EventLatency(),send=new EventLatency();
    private final LongAdder actions=new LongAdder();
    private final LongAdder packets=new LongAdder(),bytes=new LongAdder(),failures=new LongAdder();
    private final Map<Integer,long[]> viewers=new HashMap<>();
    private final TreeMap<Long,Long> pending=new TreeMap<>();
    private long sequence,previousPending,growthAt,lastSampleMs;
    private double outboundAge;
    private boolean outboundGrowing;
    public void action(long beganNs) {
        double elapsed=(System.nanoTime()-beganNs)/1_000_000.0;
        action.record(elapsed);
        actions.increment();
    }
    public double percentile(double percentile) {
        return action.percentile(percentile);
    }
    public void macroLag(long lagMs) {macro.record(Math.max(0,lagMs));}
    public void movementLag(long dueNs) {movement.record(Math.max(0,(System.nanoTime()-dueNs)/1_000_000.0));}
    public synchronized long sending(int viewer,int length) {
        long ticket=++sequence;pending.put(ticket,System.nanoTime());packets.increment();bytes.add(length+4L);
        long[] totals=viewers.computeIfAbsent(viewer,id->new long[2]);totals[0]++;totals[1]+=length+4L;
        return ticket;
    }
    public void sent(long ticket,boolean successful) {
        Long began;synchronized(this) {began=pending.remove(ticket);}
        if(began!=null) {send.record((System.nanoTime()-began)/1_000_000.0);if(!successful) failures.increment();}
    }
    public synchronized void outbound(double oldestMs,boolean growing) {outboundAge=oldestMs;outboundGrowing=growing;}
    public synchronized EventGovernor.Sample sample(long now) {
        if(now-lastSampleMs>=1000) {
            if(pending.size()>previousPending) {if(growthAt==0) growthAt=now;}
            else growthAt=0;
            outboundGrowing=growthAt>0 && now-growthAt>=5000;
            previousPending=pending.size();lastSampleMs=now;
        }
        outboundAge=pending.isEmpty()?0:Math.max(0,(System.nanoTime()-pending.firstEntry().getValue())/1_000_000.0);
        var memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double heap=memory.getUsed()*100.0/Math.max(1,memory.getMax());
        var os=ManagementFactory.getOperatingSystemMXBean();
        double cpu=os instanceof com.sun.management.OperatingSystemMXBean extended?Math.max(0,extended.getProcessCpuLoad()*100):0;
        return new EventGovernor.Sample(now,Math.max(percentile(.99),Math.max(macro.percentile(.99),movement.percentile(.99))),outboundAge,cpu,heap,outboundGrowing);
    }
    public List<String> report() {
        var s=sample(System.currentTimeMillis());
        return List.of("Applied actions="+actions.sum()+" rolling-minute latency p95/p99="+percentile(.95)+"/"+percentile(.99)+"ms",
                "Macro schedule p95/p99="+macro.percentile(.95)+"/"+macro.percentile(.99)+"ms; 50ms movement schedule="+movement.percentile(.95)+"/"+movement.percentile(.99)+"ms",
                "Process CPU="+s.cpuPercent()+"%, heap="+s.heapPercent()+"%; outbound oldest="+outboundAge+"ms growing="+outboundGrowing,
                "Actual viewer writes packets="+packets.sum()+", wire bytes="+bytes.sum()+", pending="+pendingCount()+", failed="+failures.sum()+"; flush p95/p99="+send.percentile(.95)+"/"+send.percentile(.99)+"ms",
                "Per-viewer packet/byte totals="+viewerTotals(),
                "Legacy client FPS/frame times require an actual render client; this JVM report does not verify capacity.");
    }
    public synchronized int pendingCount() {return pending.size();}
    public synchronized String viewerTotals() {Map<Integer,String> result=new TreeMap<>();viewers.forEach((id,v)->result.put(id,v[0]+"/"+v[1]));return result.toString();}
}

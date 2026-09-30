package soloMapling.ArtificialPlayer.CompanionSystem;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BossTelemetryTest {
    @Test void histogramRetainsBoundedRecentSamplesAndActualActionBytes() {
        BossTelemetry.reset();
        for(int i=0;i<1024;i++) BossTelemetry.sample(BossTelemetry.Stage.COMBAT,100_000_000);
        for(int i=0;i<1024;i++) BossTelemetry.sample(BossTelemetry.Stage.COMBAT,1_000_000);
        BossTelemetry.packet(30); BossTelemetry.packet(52);
        var snapshot=BossTelemetry.snapshot(); var timing=snapshot.timings().get(BossTelemetry.Stage.COMBAT);
        assertEquals(2048,timing.count()); assertEquals(1,timing.p50Ms()); assertEquals(1,timing.p95Ms());
        assertEquals(100,timing.maxMs()); assertEquals(2,snapshot.actionPackets()); assertEquals(82,snapshot.actionBytes());
        BossTelemetry.reset(); assertTrue(BossTelemetry.snapshot().timings().isEmpty());
    }
}

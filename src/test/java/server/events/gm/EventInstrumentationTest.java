package server.events.gm;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventInstrumentationTest {
    @Test void staleBindingsCannotReleaseNewGenerationAndMapsAreExactInstances() {
        var old=new EventTelemetry();var fresh=new EventTelemetry();var map=mock(MapleMap.class);var other=mock(MapleMap.class);
        var actor=mock(client.Character.class);when(actor.getId()).thenReturn(801);when(actor.getMap()).thenReturn(map);
        EventInstrumentation.register("old",List.of(map),old);EventInstrumentation.bindBot(801,"old");
        EventInstrumentation.register("new",List.of(map),fresh);EventInstrumentation.bindBot(801,"new");
        EventInstrumentation.retire("old");EventInstrumentation.releaseBot(801,"old");
        assertSame(fresh,EventInstrumentation.forBot(801));assertSame(fresh,EventInstrumentation.forActor(actor));
        EventInstrumentation.releaseBot(801,"new");when(actor.getMap()).thenReturn(other);assertNull(EventInstrumentation.forActor(actor));
        EventInstrumentation.retire("new");
    }
    @Test void latencyWindowExpiresAndNettyPromiseAccountingIsIdempotent() {
        AtomicLong clock=new AtomicLong(1000);var histogram=new EventLatency(clock::get);
        histogram.record(900);assertEquals(1000,histogram.percentile(.99));clock.addAndGet(60_000);assertEquals(0,histogram.percentile(.99));
        histogram.record(12);assertEquals(20,histogram.percentile(.95));
        var telemetry=new EventTelemetry();long first=telemetry.sending(1,50),second=telemetry.sending(2,100);
        assertEquals(2,telemetry.pendingCount());telemetry.sent(first,true);telemetry.sent(first,true);
        assertEquals(1,telemetry.pendingCount());assertTrue(telemetry.viewerTotals().contains("1=1/54"));
        telemetry.sent(second,false);assertEquals(0,telemetry.pendingCount());assertTrue(telemetry.report().stream().anyMatch(s->s.contains("failed=1")));
    }
}

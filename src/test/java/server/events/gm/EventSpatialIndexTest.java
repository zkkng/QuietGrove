package server.events.gm;

import org.junit.jupiter.api.Test;
import java.awt.Point;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EventSpatialIndexTest {
    @Test void negativeCellBoundariesAndSnapshotPositionsPreserveLocality() {
        var negative=new Point(-601,-600);var boundary=new Point(-1,-600);var remote=new Point(10000,10000);
        var index=new EventSpatialIndex<>(List.of(negative,boundary,remote),p->p,600);
        assertEquals(List.of(negative,boundary),index.nearby(new Point(-601,-600),600));
        negative.move(50000,50000);
        assertEquals(List.of(negative,boundary),index.nearby(new Point(-601,-600),600));
        assertTrue(index.nearby(new Point(),1).isEmpty());
    }
}

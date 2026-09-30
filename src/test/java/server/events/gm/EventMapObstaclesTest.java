package server.events.gm;

import org.junit.jupiter.api.Test;
import provider.DataProviderFactory;
import provider.wz.WZFiles;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventMapObstaclesTest {
    @Test void coconutActualWzResolvesLinkedSpikeFramesAndTransparentRest() {
        var source = DataProviderFactory.getDataProvider(WZFiles.MAP);
        var objects = EventMapObstacles.load(source.getData("Map/Map1/109080000.img"),
                name -> source.getData("Obj/" + name + ".img"));
        assertEquals(23, objects.size());
        assertEquals(19, objects.stream().filter(o -> o.damage() == 20).count());
        assertEquals(4, objects.stream().filter(o -> o.damage() == 25).count());
        var spike = objects.stream().filter(o -> o.x() == 45 && o.y() == -202).findFirst().orElseThrow();
        assertEquals(11, spike.frames().size());
        assertFalse(spike.frames().getLast().harmful());
        assertEquals(1500, spike.frames().getLast().duration());
        Point standing = new Point(45, -202);
        assertEquals(20, EventMapObstacles.collision(List.of(spike), standing, standing, 420).damage());
        assertNull(EventMapObstacles.collision(List.of(spike), standing, standing, spike.period() - 1));
        assertEquals(20, EventMapObstacles.collision(List.of(spike), standing, standing, spike.period() + 420).damage());
        assertNull(EventMapObstacles.collision(List.of(spike), new Point(45, -300), new Point(45, -300), 420));
    }
    @Test void sweptCollisionMirrorAndStrongestOverlapWithoutStacking() {
        var frame = new EventMapObstacles.Frame(100, -10, -20, 30, 0, true);
        var spike = new EventMapObstacles.Obstacle(100, 100, false, 20, List.of(frame), 100);
        var flipped = new EventMapObstacles.Obstacle(100, 100, true, 40, List.of(frame), 100);
        Point left = new Point(70, 100), right = new Point(130, 100);
        assertNull(EventMapObstacles.collision(List.of(spike), left, left, 0));
        assertEquals(40, EventMapObstacles.collision(List.of(flipped), left, left, 0).damage());
        assertEquals(20, EventMapObstacles.collision(List.of(spike), left, right, 0).damage());
        assertEquals(40, EventMapObstacles.collision(List.of(spike, flipped), left, right, 0).damage());
        assertNull(EventMapObstacles.collision(List.of(spike), new Point(100, 49), new Point(100, 49), 0));
    }
}

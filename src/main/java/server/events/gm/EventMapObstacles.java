package server.events.gm;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.DataType;
import provider.wz.WZFiles;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.*;

/** Local WZ map-object collisions, independent of monster contact and event scoring. */
public final class EventMapObstacles {
    record Frame(int duration, int left, int top, int right, int bottom, boolean harmful) {}
    record Obstacle(int x, int y, boolean flipped, int damage, List<Frame> frames, long period) {
        Frame frame(long elapsed) {
            long phase = Math.floorMod(elapsed, period);
            for (Frame frame : frames) {
                if (phase < frame.duration()) return frame;
                phase -= frame.duration();
            }
            return frames.getLast();
        }
    }
    public record Collision(int damage, int originX) {}
    private record AssetKey(String directory, int mapId) {}
    private static final Map<AssetKey, List<Obstacle>> templates = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<MapleMap, List<Obstacle>> fields = Collections.synchronizedMap(new WeakHashMap<>());
    private EventMapObstacles() {}

    /** Disk/XML work occurs before admission, never on the 50ms physics path. */
    public static void prepare(MapleMap map) {
        if (fields.containsKey(map)) return;
        AssetKey key = new AssetKey(WZFiles.MAP.getFile().toAbsolutePath().normalize().toString(), map.getId());
        fields.put(map, templates.computeIfAbsent(key, EventMapObstacles::loadMap));
    }
    private static List<Obstacle> loadMap(AssetKey key) {
        var source = DataProviderFactory.getDataProvider(WZFiles.MAP);
        String id = String.format(Locale.ROOT, "%09d", key.mapId());
        Data data = source.getData("Map/Map" + id.charAt(0) + "/" + id + ".img");
        String link = DataTool.getString("info/link", data, "");
        if (!link.isEmpty()) {
            id = String.format(Locale.ROOT, "%09d", Integer.parseInt(link));
            data = source.getData("Map/Map" + id.charAt(0) + "/" + id + ".img");
        }
        var libraries = new HashMap<String, Data>();
        return load(data, name -> libraries.computeIfAbsent(name,
                set -> source.getData("Obj/" + set + ".img")));
    }

    static List<Obstacle> load(Data map, java.util.function.Function<String, Data> library) {
        if (map == null) throw new IllegalArgumentException("Missing event map WZ");
        List<Obstacle> result = new ArrayList<>();
        for (Data layer : map) {
            Data objects = layer.getChildByPath("obj");
            if (objects == null) continue;
            for (Data object : objects) {
                String set = DataTool.getString("oS", object, "");
                if (set.isEmpty()) continue;
                Data root = library.apply(set);
                if (root == null) throw new IllegalArgumentException("Missing map object set " + set);
                String path = DataTool.getString("l0", object, "") + "/"
                        + DataTool.getString("l1", object, "") + "/" + DataTool.getString("l2", object, "");
                Data animation = resolve(root.getChildByPath(path));
                if (animation == null) throw new IllegalArgumentException("Missing map object " + set + "/" + path);
                int damage = DataTool.getInt("damage", animation, 0);
                if (damage <= 0 || DataTool.getInt("obstacle", animation, 0) == 0) continue;
                List<Data> numbered = animation.getChildren().stream().filter(f -> f.getName().matches("\\d+"))
                        .sorted(Comparator.comparingInt(f -> Integer.parseInt(f.getName()))).toList();
                List<Frame> frames = new ArrayList<>(); long period = 0;
                for (Data numberedFrame : numbered) {
                    Data frame = resolve(numberedFrame);
                    if (frame == null) throw new IllegalArgumentException("Unresolved obstacle frame " + path);
                    int delay = Math.max(1, DataTool.getInt("delay", frame, 100));
                    Point lt = DataTool.getPoint("lt", frame, null), rb = DataTool.getPoint("rb", frame, null);
                    boolean harmful = lt != null && rb != null && DataTool.getInt("a0", frame, 255) > 0
                            && DataTool.getInt("a1", frame, 255) > 0;
                    frames.add(new Frame(delay, lt == null ? 0 : lt.x, lt == null ? 0 : lt.y,
                            rb == null ? 0 : rb.x, rb == null ? 0 : rb.y, harmful));
                    period += delay;
                }
                if (frames.isEmpty()) throw new IllegalArgumentException("Obstacle has no frames " + path);
                result.add(new Obstacle(DataTool.getInt("x", object, 0), DataTool.getInt("y", object, 0),
                        DataTool.getInt("f", object, 0) != 0, damage, List.copyOf(frames), period));
            }
        }
        return List.copyOf(result);
    }

    private static Data resolve(Data data) {
        for (int hop = 0; data != null && data.getType() == DataType.UOL; hop++) {
            if (hop == 32 || !(data.getParent() instanceof Data parent)) return null;
            data = parent.getChildByPath(DataTool.getString(data));
        }
        return data;
    }

    /** Animation begins on entry, matching the client loading its map objects. Transparent rest frames are safe. */
    public static Collision collision(MapleMap map, Point previous, Point current, long sinceEntryMs) {
        List<Obstacle> obstacles = fields.get(map);
        return obstacles == null ? null : collision(obstacles, previous, current, sinceEntryMs);
    }
    static Collision collision(List<Obstacle> obstacles, Point previous, Point current, long elapsed) {
        int left = Math.min(previous.x, current.x), right = Math.max(previous.x, current.x);
        int top = Math.min(previous.y, current.y) - 50, bottom = Math.max(previous.y, current.y);
        Obstacle selected = null;
        for (Obstacle obstacle : obstacles) {
            Frame frame = obstacle.frame(elapsed);
            if (!frame.harmful()) continue;
            int x1 = obstacle.x() + (obstacle.flipped() ? -frame.right() : frame.left());
            int x2 = obstacle.x() + (obstacle.flipped() ? -frame.left() : frame.right());
            if (left <= x2 && right >= x1 && top <= obstacle.y() + frame.bottom()
                    && bottom >= obstacle.y() + frame.top()
                    && (selected == null || selected.damage() < obstacle.damage())) selected = obstacle;
        }
        return selected == null ? null : new Collision(selected.damage(), selected.x());
    }
}

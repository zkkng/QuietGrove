package gmevents;

import java.awt.Point;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** The human-requested finite native spawn scene. Does not grant GM rights or create bot population. */
public final class HenesysBossSummons {
    public static synchronized void agentmain(String basename, Instrumentation instrumentation) throws Exception {
        if (!basename.matches("henesys-human-20260930-[a-z0-9-]+")) throw new IllegalArgumentException("Unexpected trial identity");
        Path folder = Path.of("logs/event-capacity"); Files.createDirectories(folder);
        Path report = folder.resolve(basename + ".csv");
        Class<?> serverClass = Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(c -> c.getName().equals("net.server.Server")).findFirst().orElseThrow();
        ClassLoader loader = serverClass.getClassLoader();
        Object server = serverClass.getMethod("getInstance").invoke(null);
        Object world = call(server, "getWorld", 0);
        Object actor = call(call(world, "getPlayerStorage"), "getCharacterByName", "lulu");
        Class<?> characterType = Class.forName("client.Character", false, loader);
        Class<?> botHelpers = Class.forName("soloMapling.ArtificialPlayer.BotHelpers", false, loader);
        if (actor != null && (boolean)botHelpers.getMethod("isBot", characterType).invoke(null, actor))
            throw new IllegalStateException("Target must be the observed human client");
        Object channel = call(server, "getChannel", 0, 1);
        Object map = call(call(channel, "getMapFactory"), "getMap", 100000000);
        Object portal = call(map, "getPortal", 1);
        if (portal == null) throw new IllegalStateException("Henesys portal missing");
        Point entry = (Point)call(portal, "getPosition");
        var area = (java.awt.Rectangle)call(map, "getMapArea");
        List<Point> positions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Point ground = (Point)call(map, "getPointBelow", new Point(entry.x - 240 + i % 20 * 24, entry.y - 10));
            if (ground == null || !area.contains(ground)) throw new IllegalStateException("Invalid native ground placement");
            positions.add(new Point(ground.x, ground.y - 1));
        }
        var factory = Class.forName("server.life.LifeFactory", true, loader);
        Method getMonster = factory.getMethod("getMonster", int.class);
        int[] types = {6130101, 8130100, 8150000}, counts = {40, 30, 30};
        List<Object> monsters = new ArrayList<>();
        for (int type = 0; type < types.length; type++) for (int n = 0; n < counts[type]; n++) {
            Object monster = getMonster.invoke(null, types[type]);
            if (monster == null) throw new IllegalStateException("Missing native monster template " + types[type]);
            monsters.add(monster);
        }
        try (var writer = Files.newBufferedWriter(report, StandardOpenOption.CREATE_NEW)) {
            writer.write("# requested finite native summons; new incident framework not required or certified\n");
            writer.write("# utc=" + Instant.now() + ",world=0,channel=1,map=100000000\n");
            writer.write("template,objectId,x,y\n"); writer.flush();
            if (actor != null && (boolean)call(actor, "isAlive") && !(boolean)call(actor, "isChangingMaps")) {
                synchronized (actor) {
                    call(actor, "changeMap", map, portal);
                    if (call(actor, "getMap") != map) throw new IllegalStateException("Human map transition failed");
                }
            }
            int spawned = 0;
            for (int i = 0; i < monsters.size(); i++) {
                Object monster = monsters.get(i); Point position = positions.get(i);
                call(monster, "setPosition", position); call(map, "spawnMonster", monster);
                int oid = (int)call(monster, "getObjectId");
                if (call(map, "getMonsterByOid", oid) != monster) throw new IllegalStateException("Native spawn was rejected");
                writer.write(call(monster, "getId") + "," + oid + "," + position.x + "," + position.y + "\n");
                writer.flush(); spawned++;
            }
            writer.write("# confirmedSpawned=" + spawned + "\n");
            System.out.println("Human Henesys trial: confirmed " + spawned + " native monsters; report=" + report);
        }
    }
    private static Object call(Object object, String method, Object... args) throws Exception {
        for (Method m : object.getClass().getMethods()) if (m.getName().equals(method) && m.getParameterCount() == args.length)
            try { return m.invoke(object, args); } catch (IllegalArgumentException wrongOverload) { }
        throw new NoSuchMethodException(method);
    }
}

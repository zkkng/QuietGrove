package worldrespawn;

import java.io.BufferedWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finite, read-only sampler of already loaded ordinary-map respawn state. */
public final class WorldRespawnProbe {
    private WorldRespawnProbe() { }

    public static void agentmain(String argument, Instrumentation instrumentation) throws Exception {
        String[] args = argument.split(",", -1);
        if (args.length != 5 || !args[0].matches("[A-Za-z0-9._-]{1,80}"))
            throw new IllegalArgumentException("basename,seconds,world,channel,mapId|mapId");
        int seconds = Integer.parseInt(args[1]);
        int world = Integer.parseInt(args[2]);
        int channel = Integer.parseInt(args[3]);
        List<Integer> mapIds = Arrays.stream(args[4].split("\\|", -1)).map(Integer::parseInt).toList();
        if (seconds < 2 || seconds > 600 || world < 0 || channel < 1 || mapIds.isEmpty()
                || mapIds.size() > 8 || Set.copyOf(mapIds).size() != mapIds.size()
                || mapIds.stream().anyMatch(id -> id < 100000000 || id > 999999999))
            throw new IllegalArgumentException("2..600 seconds, world >= 0, channel >= 1, one to eight distinct map IDs");

        Class<?> serverType = Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(type -> type.getName().equals("net.server.Server")).findFirst().orElseThrow();
        ClassLoader loader = serverType.getClassLoader();
        Object server = serverType.getMethod("getInstance").invoke(null);
        Object channelObject = serverType.getMethod("getChannel", int.class, int.class)
                .invoke(server, world, channel);
        if (channelObject == null) throw new IllegalArgumentException("Missing world/channel");
        Object mapManager = channelObject.getClass().getMethod("getMapFactory").invoke(channelObject);
        Class<?> configType = Class.forName("config.YamlConfig", false, loader);
        Object config = configType.getField("config").get(null);
        Object serverConfig = configType.getField("server").get(config);
        long intervalMs = serverConfig.getClass().getField("RESPAWN_INTERVAL").getLong(serverConfig);
        boolean fullRespawn = serverConfig.getClass().getField("USE_ENABLE_FULL_RESPAWN")
                .getBoolean(serverConfig);

        Path folder = Path.of("logs/world-respawn");
        Files.createDirectories(folder);
        Path output = folder.resolve(args[0] + ".csv");
        // Reserve the name before returning from agentmain; never overwrite another run.
        BufferedWriter writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW);
        writer.write("utc,elapsedSec,world,channel,mapId,loaded,occupants,staticPoints,zeroMobTime,"
                + "deniedPoints,eligiblePoints,staticPointOwned,spawnedCount,visibleAlive,"
                + "expectedTarget,respawnIntervalMs,fullRespawn\n");
        writer.flush();
        Thread sampler = new Thread(() -> sample(writer, output, mapManager, mapIds, seconds, world, channel,
                intervalMs, fullRespawn), "world-respawn-finite-observer");
        sampler.setDaemon(true);
        sampler.start();
    }

    @SuppressWarnings("unchecked")
    private static void sample(BufferedWriter writer, Path output, Object mapManager, List<Integer> mapIds,
                               int seconds, int world, int channel, long intervalMs, boolean fullRespawn) {
        try (writer) {
            Method getMaps = mapManager.getClass().getMethod("getMaps");
            for (int elapsed = 0; elapsed < seconds; elapsed++) {
                Map<Integer, ?> loaded = (Map<Integer, ?>) getMaps.invoke(mapManager);
                for (int id : mapIds) {
                    Object map = loaded.get(id);
                    if (map == null) {
                        writer.write(Instant.now() + "," + elapsed + "," + world + "," + channel + ","
                                + id + ",false,,,,,,,,,," + intervalMs + "," + fullRespawn + "\n");
                        continue;
                    }
                    Class<?> type = map.getClass();
                    int occupants = ((Collection<?>) type.getMethod("getCharacters").invoke(map)).size();
                    int spawned = (int) type.getMethod("getSpawnedMonstersOnMap").invoke(map);
                    Collection<?> monsters = (Collection<?>) type.getMethod("getAllMonsters").invoke(map);
                    int alive = 0;
                    for (Object monster : monsters)
                        if ((boolean) monster.getClass().getMethod("isAlive").invoke(monster)) alive++;
                    Method getSpawns = type.getDeclaredMethod("getMonsterSpawn");
                    getSpawns.setAccessible(true);
                    Collection<?> points = (Collection<?>) getSpawns.invoke(map);
                    int zeroTime = 0, denied = 0, eligible = 0, owned = 0;
                    for (Object point : points) {
                        Class<?> pointType = point.getClass();
                        if ((int) pointType.getMethod("getMobTime").invoke(point) == 0) zeroTime++;
                        if ((boolean) pointType.getMethod("getDenySpawn").invoke(point)) denied++;
                        if ((boolean) pointType.getMethod("shouldSpawn").invoke(point)) eligible++;
                        owned += (int) pointType.getMethod("getSpawned").invoke(point);
                    }
                    int target = fullRespawn ? points.size()
                            : (int) Math.ceil((0.70 + 0.05 * Math.min(6, occupants)) * points.size());
                    writer.write(Instant.now() + "," + elapsed + "," + world + "," + channel + ","
                            + id + ",true," + occupants + "," + points.size() + "," + zeroTime + ","
                            + denied + "," + eligible + "," + owned + "," + spawned + ","
                            + alive + "," + target + "," + intervalMs + "," + fullRespawn + "\n");
                }
                writer.flush();
                if (elapsed + 1 < seconds) Thread.sleep(1000);
            }
        } catch (Exception failure) {
            System.err.println("World respawn observer stopped: " + failure);
            try {
                Files.writeString(output.resolveSibling(output.getFileName() + ".error.txt"),
                        Instant.now() + " " + failure + "\n", StandardOpenOption.CREATE_NEW);
            } catch (Exception reportFailure) {
                System.err.println("World respawn observer could not write error report: " + reportFailure);
            }
        }
    }
}

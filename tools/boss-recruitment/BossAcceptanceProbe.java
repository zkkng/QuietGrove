package bossacceptance;

import client.Character;
import client.inventory.InventoryType;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.*;

/** Finite read-only observations of existing hunts. Never starts a task or alters gameplay. */
public final class BossAcceptanceProbe {
    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        String[] args = arguments.split(",", -1);
        if (args.length != 2 || !args[0].matches("[a-zA-Z0-9._-]{1,80}"))
            throw new IllegalArgumentException("report-basename,seconds");
        int seconds = Integer.parseInt(args[1]);
        if (seconds < 1 || seconds > 600) throw new IllegalArgumentException("seconds 1..600");
        Path folder = Path.of("logs/boss-acceptance");
        Files.createDirectories(folder);
        Path output = folder.resolve(args[0] + ".jsonl");
        // Reserve the report before starting so duplicate names fail visibly to the operator.
        var writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW);
        Thread observer = new Thread(() -> {
            try (writer) {
                for (int tick = 0; tick < seconds; tick++) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("utc", Instant.now().toString());
                    sample.put("sample", tick);
                    sample.put("clientFpsVerified", false);
                    sample.put("telemetry", BossTelemetry.snapshot());
                    sample.put("huntReports", BossRuntime.get().metrics());
                    List<Map<String, Object>> actors = new ArrayList<>();
                    for (var task : CompanionTaskService.shared().tasks()) {
                        if (task.objective() != CompanionTaskService.Objective.FIELD_BOSS
                                && task.objective() != CompanionTaskService.Objective.RESTING) continue;
                        var bot = CharacterStorage.getBotById(task.botId());
                        Map<String, Object> actor = new LinkedHashMap<>();
                        actor.put("task", task);
                        actor.put("registered", bot != null);
                        if (bot != null) {
                            Character c = bot.getChr();
                            actor.put("map", mapKey(c.getMap()));
                            actor.put("level", c.getLevel());
                            actor.put("hp", c.getHp());
                            actor.put("mp", c.getMp());
                            actor.put("alive", c.isAlive());
                            actor.put("exp", c.getExp());
                            actor.put("mesos", c.getMeso());
                            Map<Integer, Long> items = new TreeMap<>();
                            for (var type : List.of(InventoryType.USE, InventoryType.ETC))
                                for (var item : c.getInventory(type).list())
                                    items.merge(item.getItemId(), (long) item.getQuantity(), Long::sum);
                            actor.put("useAndEtcItems", items);
                        }
                        actors.add(actor);
                    }
                    sample.put("actors", actors);
                    List<Map<String, Object>> maps = new ArrayList<>();
                    for (MapleMap map : BossRuntime.get().activeMaps()) {
                        Map<String, Object> scene = new LinkedHashMap<>();
                        scene.put("map", mapKey(map));
                        List<Map<String, Object>> monsters = new ArrayList<>();
                        for (var monster : map.getAllMonsters()) {
                            if (!BossRegistry.catalogMonster(monster.getId())) continue;
                            Map<String, Object> observed = new LinkedHashMap<>();
                            observed.put("objectId", monster.getObjectId());
                            observed.put("template", monster.getId());
                            observed.put("root", monster.getEncounterId());
                            observed.put("rootTemplate", monster.getEncounterRootTemplate());
                            observed.put("incidentOwner", monster.getIncidentOwner());
                            observed.put("hp", monster.getHp());
                            observed.put("alive", monster.isAlive());
                            observed.put("marker", monster.isEncounterMarker());
                            Character controller = monster.getController();
                            observed.put("controllerId", controller == null ? null : controller.getId());
                            monsters.add(observed);
                        }
                        scene.put("monsters", monsters);
                        maps.add(scene);
                    }
                    sample.put("maps", maps);
                    writer.write(json(sample));
                    writer.newLine();
                    writer.flush();
                    if (tick + 1 < seconds) Thread.sleep(1000);
                }
            } catch (Exception failure) {
                System.err.println("Boss acceptance observer failed: " + failure);
            }
        }, "boss-finite-acceptance-observer");
        observer.setDaemon(true);
        observer.start();
    }

    private static String mapKey(MapleMap map) {
        return map == null ? null : map.getWorld() + ":" + map.getChannelServer().getId()
                + ":" + map.getId() + ":" + Integer.toUnsignedString(System.identityHashCode(map));
    }

    private static String json(Object value) throws ReflectiveOperationException {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringJoiner out = new StringJoiner(",", "{", "}");
            for (var entry : map.entrySet()) out.add(json(entry.getKey().toString()) + ":" + json(entry.getValue()));
            return out.toString();
        }
        if (value instanceof Collection<?> list) {
            StringJoiner out = new StringJoiner(",", "[", "]");
            for (Object item : list) out.add(json(item));
            return out.toString();
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> components = new LinkedHashMap<>();
            for (var component : value.getClass().getRecordComponents())
                components.put(component.getName(), component.getAccessor().invoke(value));
            return json(components);
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') quoted.append('\\').append(c);
            else if (c < 32) quoted.append(String.format("\\u%04x", (int) c));
            else quoted.append(c);
        }
        return quoted.append('"').toString();
    }
}

package gmevents;

import java.awt.Point;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import net.server.Server;
import server.events.gm.IncidentService;
import server.events.gm.WaveInvasionService;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import soloMapling.server.BotTickService;

/** Finite read-only position and scheduling snapshot of the existing Henesys population. */
public final class HenesysFreezeProbeV2 {
    private record Before(Point position, String role, String wheel) {}

    private static String wheel(int id) {
        try {
            Field entriesField = BotTickService.class.getDeclaredField("ENTRIES");
            entriesField.setAccessible(true);
            Object entry = ((Map<?, ?>) entriesField.get(null)).get(id);
            if (entry == null) return "absent";
            Field dueField = entry.getClass().getDeclaredField("nextDueMs");
            Field tickingField = entry.getClass().getDeclaredField("ticking");
            dueField.setAccessible(true);
            tickingField.setAccessible(true);
            long due = dueField.getLong(entry);
            boolean ticking = ((java.util.concurrent.atomic.AtomicBoolean) tickingField.get(entry)).get();
            return (due == Long.MAX_VALUE ? "MAX" : Long.toString(due)) + "/" + ticking;
        } catch (Exception failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    public static void agentmain(String name, Instrumentation ignored) {
        if (name == null || !name.matches("[a-zA-Z0-9._-]{1,80}"))
            throw new IllegalArgumentException("report basename");
        Thread observer = new Thread(() -> observe(name), "henesys-freeze-readonly");
        observer.setDaemon(true);
        observer.start();
    }

    private static void observe(String name) {
        try {
            var channel = Server.getInstance().getChannel(0, 1);
            var map = channel.getMapFactory().getMap(100000000);
            Map<Integer, Before> before = new TreeMap<>();
            for (BotSM bot : new ArrayList<>(CharacterStorage.getAllBots().values())) {
                var actor = bot.getChr();
                if (actor == null || actor.getMap() != map) continue;
                int id = actor.getId();
                before.put(id, new Before(new Point(actor.getPosition()), bot.getClass().getSimpleName(), wheel(id)));
            }
            String first = "utc=" + Instant.now() + " show=" + WaveInvasionService.getInstance().status(channel)
                    + " incidents=" + IncidentService.getInstance().status(channel)
                    + " bots=" + before.size() + " monsters=" + map.getAllMonsters().size();
            Thread.sleep(30_000);
            Map<String, int[]> summary = new TreeMap<>();
            StringBuilder detail = new StringBuilder();
            for (BotSM bot : new ArrayList<>(CharacterStorage.getAllBots().values())) {
                var actor = bot.getChr();
                if (actor == null || actor.getMap() != map) continue;
                int id = actor.getId();
                Before prior = before.get(id);
                if (prior == null) continue;
                double displacement = prior.position.distance(actor.getPosition());
                int[] counts = summary.computeIfAbsent(prior.role, unused -> new int[7]);
                counts[0]++;
                if (displacement >= 10) counts[1]++;
                if (bot.getRunning()) counts[2]++;
                if (bot.activityPaused()) counts[3]++;
                if (BotTickService.isRegistered(id)) counts[4]++;
                if (CompanionTaskService.shared().eventLease(id).isPresent()) counts[5]++;
                if (actor.isAlive()) counts[6]++;
                detail.append("actor=").append(id).append(" role=").append(prior.role)
                        .append(" movedPx=").append(Math.round(displacement))
                        .append(" running=").append(bot.getRunning()).append(" paused=").append(bot.activityPaused())
                        .append(" scheduled=").append(BotTickService.isRegistered(id))
                        .append(" leased=").append(CompanionTaskService.shared().eventLease(id).isPresent())
                        .append(" wheelBefore=").append(prior.wheel).append(" wheelAfter=").append(wheel(id))
                        .append(" state=").append(bot.getState()).append(" alive=").append(actor.isAlive()).append('\n');
            }
            String second = "utc=" + Instant.now() + " show=" + WaveInvasionService.getInstance().status(channel)
                    + " incidents=" + IncidentService.getInstance().status(channel)
                    + " monsters=" + map.getAllMonsters().size();
            Path folder = Path.of("logs/event-capacity");
            Files.createDirectories(folder);
            StringBuilder output = new StringBuilder(first).append('\n').append(second).append('\n');
            summary.forEach((role, c) -> output.append("role=").append(role)
                    .append(" matched=").append(c[0]).append(" moved10px=").append(c[1])
                    .append(" running=").append(c[2]).append(" paused=").append(c[3])
                    .append(" scheduled=").append(c[4]).append(" leased=").append(c[5])
                    .append(" alive=").append(c[6]).append('\n'));
            output.append(detail);
            Files.writeString(folder.resolve(name + ".txt"), output,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (Exception failure) {
            System.err.println("Henesys freeze probe failed: " + failure);
        }
    }
}

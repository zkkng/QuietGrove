package bossacceptance;

import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import net.server.Server;
import server.events.gm.*;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import soloMapling.server.BotTickService;

/** Finite observer of real lease release and restored FSMs; no gameplay mutations. */
public final class HostedLifecycleProbe {
    public static void agentmain(String name, Instrumentation instrumentation) {
        if (name == null || !name.matches("[a-zA-Z0-9._-]{1,80}"))
            throw new IllegalArgumentException("basename");
        Thread observer = new Thread(() -> observe(name), "hosted-finite-lifecycle");
        observer.setDaemon(true);
        observer.start();
    }

    private static void observe(String name) {
        try {
            Path folder = Path.of("logs/boss-acceptance");
            Files.createDirectories(folder);
            var channel = Server.getInstance().getChannel(0, 1);
            var map = channel.getMapFactory().getMap(100000000);
            var tracked = new LinkedHashSet<CompanionTaskService.EventLease>();
            var lastState = new HashMap<CompanionTaskService.EventLease, String>();
            var tasks = CompanionTaskService.shared();
            try (var writer = Files.newBufferedWriter(folder.resolve(name + ".txt"), StandardOpenOption.CREATE_NEW)) {
                for (int second = 0; second < 420; second++) {
                    var leases = tasks.eventLeases();
                    for (var lease : leases)
                        if (lease.worldId() == 0 && lease.channelId() == 1 && lease.committed()) tracked.add(lease);
                    writer.write("utc=" + Instant.now() + " companionsEnabled=" + config.YamlConfig.config.server.COMPANIONS_ENABLED
                            + " show=" + WaveInvasionService.getInstance().status(channel)
                            + " incidents=" + IncidentService.getInstance().status(channel)
                            + " activeLeases=" + leases.size() + " tracked=" + tracked.size()
                            + " monsters=" + map.getAllMonsters().size() + " mapTelemetry=" + (EventInstrumentation.forMap(map) != null) + "\n");
                    for (var lease : tracked) {
                        var bot = CharacterStorage.getBotById(lease.botId());
                        var current = tasks.eventLease(lease.botId()).orElse(null);
                        String state = "currentLease=" + (current == null ? "none" : current.generation())
                                + " mapLease=" + EventMapLeases.owned(map, lease.eventId())
                                + " botTelemetry=" + (EventInstrumentation.forBot(lease.botId()) != null);
                        if (bot != null) {
                            var actor = bot.getChr();
                            state += " fsm=" + bot.getClass().getSimpleName() + " running=" + bot.getRunning()
                                    + " scheduled=" + BotTickService.isRegistered(lease.botId()) + " alive=" + actor.isAlive()
                                    + " map=" + actor.getMapId() + " changing=" + actor.isChangingMaps()
                                    + " name=" + actor.getName() + " display=" + GmHostPresentation.displayName(actor)
                                    + " hostGear=" + (GmHostPresentation.equipment(actor) != null);
                        } else state += " unregistered=true";
                        if (!state.equals(lastState.put(lease, state)))
                            writer.write("actor=" + lease.botId() + " generation=" + lease.generation() + " role=" + lease.role()
                                    + " prior=" + lease.prior() + " " + state + "\n");
                    }
                    writer.flush();
                    if (second + 1 < 420) Thread.sleep(1000);
                }
            }
        } catch (Exception failure) {
            System.err.println("Hosted lifecycle observer failed: " + failure);
        }
    }
}

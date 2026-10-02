package hybrid.build;

import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.time.Instant;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.*;
import soloMapling.ArtificialPlayer.HybridPilot.*;
import soloMapling.server.BotTickService;

/** Explicit, finite operator action. No class redefinition, simulated human or monster spawn. */
public final class TrialProbe {
    public static void agentmain(String args, Instrumentation ignored) {
        String[] parts = args.split(":", -1);
        if (parts.length != 2 || !parts[0].matches("spawn|status|off") || !parts[1].matches("hybrid-[a-zA-Z0-9-]{1,60}"))
            throw new IllegalArgumentException("mode:report-name");
        var worker = new Thread(() -> run(parts[0], parts[1]), "hybrid-trial-operator");
        worker.setDaemon(true);
        worker.start();
    }
    private static void run(String action, String name) {
        StringBuilder report = new StringBuilder("utc=" + Instant.now() + " action=" + action + "\n");
        boolean created = false;
        try {
            var service = HybridPilotService.get();
            if (action.equals("spawn")) {
                if (CharacterStorage.getAllBots().values().stream().anyMatch(b -> b instanceof HybridPilotBot))
                    throw new IllegalStateException("Pilot already exists; use status, here or off explicitly");
                service.spawnHenesys(0, 1, 3);
                created = true;
            } else if (action.equals("off")) report.append("removed=").append(service.off()).append('\n');
            int count = 0;
            for (var actor : CharacterStorage.getAllBots().values()) if (actor instanceof HybridPilotBot pilot) {
                var body = actor.getChr();
                var assessment = BossSuitability.assess(body, BossRegistry.get("mushmom"), BossIntentParser.Role.ANY);
                report.append("actor=").append(pilot.status()).append(" channel=").append(body.getClient().getChannel())
                        .append(" job=").append(body.getJob()).append(" level=").append(body.getLevel())
                        .append(" position=").append(body.getPosition()).append(" registered=").append(BotTickService.isRegistered(body.getId()))
                        .append(" observed=").append(HybridPilotBot.observed(body.getMap()))
                        .append(" mushmomSuitable=").append(assessment.suitable()).append(" reason=").append(assessment.reason()).append('\n');
                if (created && (!assessment.suitable() || body.getMapId() != 100000000 || !BotTickService.isRegistered(body.getId())))
                    throw new IllegalStateException("Spawn verification failed for " + body.getName());
                count++;
            }
            if (created && count != 3) throw new IllegalStateException("Expected exactly three pilots");
            report.append("result=ok count=").append(count).append('\n');
        } catch (Throwable failure) {
            report.append("result=failed error=").append(failure).append('\n');
            if (created) try { report.append("rollbackRemoved=").append(HybridPilotService.get().off()).append('\n'); }
            catch (Throwable cleanup) { report.append("cleanupFailed=").append(cleanup).append('\n'); }
        }
        try {
            Path path = Path.of("/opt/solomapling/logs/event-capacity", name + ".txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, report);
        } catch (Exception failure) { throw new IllegalStateException("Cannot save trial report", failure); }
    }
}

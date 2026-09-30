package bossacceptance;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import server.events.gm.EventBotRuntime;

/** One-shot census and virtual-thread dump. No actor, task, configuration or population mutation. */
public final class BossStartupProbe {
    public static void agentmain(String name, Instrumentation instrumentation) throws Exception {
        if (name == null || !name.matches("[a-zA-Z0-9._-]{1,80}")) throw new IllegalArgumentException("basename");
        Path folder = Path.of("logs/boss-acceptance").toAbsolutePath();
        Files.createDirectories(folder);
        Path output = folder.resolve(name + "-census.txt");
        try (var writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW)) {
            writer.write("utc=" + Instant.now() + "\n");
            Map<String,Integer> counts = new TreeMap<>();
            for (var bot : new ArrayList<>(CharacterStorage.getAllBots().values())) {
                var actor = bot.getChr();
                String flags = bot.getClass().getSimpleName() + ";running=" + bot.getRunning()
                        + ";paused=" + bot.activityPaused() + ";state=" + bot.getState();
                if (actor != null) flags += ";alive=" + actor.isAlive() + ";gm=" + actor.isGM()
                        + ";world=" + actor.getWorld() + ";channel=" + (actor.getClient() == null ? -1 : actor.getClient().getChannel())
                        + ";map=" + actor.getMapId() + ";changing=" + actor.isChangingMaps()
                        + ";party=" + (actor.getParty() != null) + ";trade=" + (actor.getTrade() != null)
                        + ";instance=" + (actor.getEventInstance() != null) + ";shop=" + (actor.getPlayerShop() != null)
                        + ";supportedRole=" + (EventBotRuntime.prior(bot) != null)
                        + ";dialogue=" + (!bot.getInteractors().getListRespondants().isEmpty() || !bot.getInteractors().getListInquirer().isEmpty());
                counts.merge(flags,1,Integer::sum);
            }
            for (var entry : counts.entrySet()) writer.write(entry.getValue() + " " + entry.getKey() + "\n");
        }
        // Unlike Thread.getAllStackTraces, the JDK diagnostic dump includes tracked virtual threads.
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpThreads(
                folder.resolve(name + "-threads.json").toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
    }
}

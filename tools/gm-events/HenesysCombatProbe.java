package gmevents;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.server.Server;
import server.events.gm.IncidentService;
import server.events.gm.WaveInvasionService;
import server.life.Monster;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;

/** Read-only 20-second HP/death evidence for an already active hosted wave. */
public final class HenesysCombatProbe {
    public static void agentmain(String name, Instrumentation ignored) {
        if (name == null || !name.matches("[a-zA-Z0-9._-]{1,80}")) throw new IllegalArgumentException("basename");
        Thread observer = new Thread(() -> observe(name), "henesys-combat-readonly");
        observer.setDaemon(true);
        observer.start();
    }

    private static void observe(String name) {
        try {
            var channel = Server.getInstance().getChannel(0, 1);
            var map = channel.getMapFactory().getMap(100000000);
            Map<Integer, Long> hpBefore = new HashMap<>();
            Map<Integer, Integer> botHpBefore = new HashMap<>();
            String first = snapshot(map, hpBefore, botHpBefore);
            Thread.sleep(20_000);
            Map<Integer, Long> hpAfter = new HashMap<>();
            Map<Integer, Integer> botHpAfter = new HashMap<>();
            String second = snapshot(map, hpAfter, botHpAfter);
            StringBuilder detail = new StringBuilder();
            long mobHpLoss = 0;
            for (Monster monster : new ArrayList<>(map.getAllMonsters())) {
                Long before = hpBefore.get(monster.getObjectId());
                if (before == null) continue;
                long after = monster.getHp();
                long loss = Math.max(0, before - after);
                mobHpLoss += loss;
                detail.append("mob oid=").append(monster.getObjectId()).append(" id=").append(monster.getId())
                        .append(" boss=").append(monster.getStats().isBoss()).append(" hpBefore=").append(before)
                        .append(" hpAfter=").append(after).append(" loss=").append(loss).append('\n');
            }
            long botHpLoss = 0;
            int botDeaths = 0;
            for (var actor : new ArrayList<>(map.getCharacters())) {
                if (!BotHelpers.isBot(actor)) continue;
                Integer before = botHpBefore.get(actor.getId());
                if (before == null) continue;
                botHpLoss += Math.max(0, before - actor.getHp());
                if (before > 0 && !actor.isAlive()) botDeaths++;
            }
            Path folder = Path.of("logs/event-capacity");
            Files.createDirectories(folder);
            String body = "utc=" + Instant.now() + '\n' + first + '\n' + second + '\n'
                    + "matchedMobHpLoss=" + mobHpLoss + " matchedBotHpLoss=" + botHpLoss
                    + " matchedBotDeaths=" + botDeaths + '\n' + detail
                    + "show=" + WaveInvasionService.getInstance().status(channel) + '\n'
                    + "incidents=" + IncidentService.getInstance().status(channel) + '\n';
            Files.writeString(folder.resolve(name + ".txt"), body, StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) {
            System.err.println("Henesys combat read-only probe failed: " + failure);
        }
    }

    private static String snapshot(server.maps.MapleMap map, Map<Integer, Long> mobHp,
                                   Map<Integer, Integer> botHp) {
        int humans = 0, aliveBots = 0, deadBots = 0, leasedBots = 0;
        for (Monster monster : new ArrayList<>(map.getAllMonsters())) mobHp.put(monster.getObjectId(), (long) monster.getHp());
        for (var actor : new ArrayList<>(map.getCharacters())) {
            if (!BotHelpers.isBot(actor)) { humans++; continue; }
            botHp.put(actor.getId(), actor.getHp());
            if (actor.isAlive()) aliveBots++; else deadBots++;
            if (CompanionTaskService.shared().eventLease(actor.getId()).isPresent()) leasedBots++;
        }
        return "time=" + Instant.now() + " mobs=" + mobHp.size() + " humans=" + humans + " aliveBots=" + aliveBots
                + " deadBots=" + deadBots + " leasedBots=" + leasedBots;
    }
}

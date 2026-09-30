package bossacceptance;

import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.time.Instant;
import net.server.Server;
import server.events.gm.*;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;

/** Finite read-only incident measurements; never starts a show or changes bots. */
public final class HostedTelemetryProbe {
    public static void agentmain(String name,Instrumentation instrumentation) {
        if(name==null || !name.matches("[a-zA-Z0-9._-]{1,80}"))throw new IllegalArgumentException("basename");
        Thread observer=new Thread(()->observe(name),"hosted-finite-telemetry");observer.setDaemon(true);observer.start();
    }
    private static void observe(String name) {
        try {
            Path folder=Path.of("logs/boss-acceptance");Files.createDirectories(folder);
            var channel=Server.getInstance().getChannel(0,1);var map=channel.getMapFactory().getMap(100000000);
            try(var writer=Files.newBufferedWriter(folder.resolve(name+".txt"),StandardOpenOption.CREATE_NEW)) {
                for(int second=0;second<180;second++) {
                    writer.write("utc="+Instant.now()+" show="+WaveInvasionService.getInstance().status(channel)
                            +" incident="+IncidentService.getInstance().status(channel)+"\n");
                    var telemetry=EventInstrumentation.forMap(map);
                    if(telemetry!=null)for(var line:telemetry.report())writer.write(line+"\n");
                    for(var monster:map.getAllMonsters())writer.write("monster="+monster.getId()+" oid="+monster.getObjectId()
                            +" hp="+monster.getHp()+" position="+monster.getPosition()+" root="+monster.getEncounterId()+"\n");
                    for(var bot:CharacterStorage.getAllBots().values()) {
                        var actor=bot.getChr();if(actor==null)continue;
                        var lease=CompanionTaskService.shared().eventLease(actor.getId()).orElse(null);
                        if(lease!=null && lease.committed())writer.write("actor="+actor.getId()+" role="+lease.role()+" hp="+actor.getHp()
                                +" max="+actor.getCurrentMaxHp()+" level="+actor.getLevel()+" job="+actor.getJob()+" map="+actor.getMapId()
                                +" position="+actor.getPosition()+" running="+bot.getRunning()+" state="+bot.getState()
                                +" incidentActor="+soloMapling.ArtificialPlayer.CompanionSystem.BossMonsterController.incidentActor(actor)+"\n");
                    }
                    writer.flush();Thread.sleep(1000);
                }
            }
        }catch(Exception failure){System.err.println("Hosted telemetry observer: "+failure.getClass().getSimpleName());}
    }
}

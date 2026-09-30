package bossacceptance;

import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.time.Instant;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.server.EventMessageSystem.EventBus;

/** Read-only snapshot of actual host HP and canonical events after a failed hosted trial. */
public final class HostedFailureProbe {
    public static void agentmain(String name, Instrumentation instrumentation) throws Exception {
        if (name == null || !name.matches("[a-zA-Z0-9._-]{1,80}")) throw new IllegalArgumentException("basename");
        Path folder=Path.of("logs/boss-acceptance");Files.createDirectories(folder);
        try(var writer=Files.newBufferedWriter(folder.resolve(name+".txt"),StandardOpenOption.CREATE_NEW)) {
            writer.write("utc="+Instant.now()+"\n");
            for(var bot:CharacterStorage.getAllBots().values()) {
                var actor=bot.getChr();
                if(actor!=null && actor.getName().equals("xFashionMS"))
                    writer.write("host id="+actor.getId()+" hp="+actor.getHp()+" maxHp="+actor.getCurrentMaxHp()
                            +" map="+actor.getMapId()+" role="+bot.getClass().getSimpleName()+"\n");
            }
            for(var event:EventBus.getInstance().getEventStore().getEventsInTimeRange(1790764570000L,1790764600000L))
                writer.write(event.getTimestamp()+" "+event.getType()+" "+event.getPlayerName()+" "+event.getPlayerId()
                        +" "+event.getMessage()+" "+event.getIncident()+"\n");
        }
    }
}

package gmevents;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Fixed own-server operator control and finite actual HP/death observations; never a capacity approval. */
public final class HenesysWaveControl {
    public static synchronized void agentmain(String action, Instrumentation instrumentation) throws Exception {
        if (!Set.of("start", "stop", "status").contains(action)) throw new IllegalArgumentException("start|stop|status");
        Class<?> serverType=Arrays.stream(instrumentation.getAllLoadedClasses()).filter(c->c.getName().equals("net.server.Server")).findFirst().orElseThrow();
        ClassLoader loader=serverType.getClassLoader();Object server=serverType.getMethod("getInstance").invoke(null);
        Object channel=call(server,"getChannel",0,1),map=call(call(channel,"getMapFactory"),"getMap",100000000);
        Object waves=Class.forName("server.events.gm.WaveInvasionService",true,loader).getMethod("getInstance").invoke(null);
        String result=String.valueOf(call(waves,action.equals("start")?"startHenesys":action.equals("stop")?"stopChannel":"status",channel));
        Path folder=Path.of("logs/event-capacity");Files.createDirectories(folder);
        String name="henesys-managed-"+System.currentTimeMillis();
        Files.writeString(folder.resolve(name+"-"+action+".txt"),"utc="+Instant.now()+"\n"+result+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("Henesys managed "+action+": "+result);
        if(action.equals("start") && result.startsWith("Hosted event ")) {
            Class<?> character=Class.forName("client.Character",false,loader);
            Method isBot=Class.forName("soloMapling.ArtificialPlayer.BotHelpers",false,loader).getMethod("isBot",character);
            Object incidents=Class.forName("server.events.gm.IncidentService",true,loader).getMethod("getInstance").invoke(null);
            Thread sampler=new Thread(()->sample(folder.resolve(name+".csv"),map,waves,incidents,channel,isBot),"gm-henesys-finite-observer");
            sampler.setDaemon(true);sampler.start();
        }
    }
    private static void sample(Path output,Object map,Object waves,Object incidents,Object channel,Method isBot) {
        Map<Integer,Long> monsterHp=new HashMap<>();Map<Integer,Integer> botHp=new HashMap<>();Map<Integer,Boolean> alive=new HashMap<>();
        long observedDamage=0,observedHpLoss=0,deaths=0;
        try(var writer=Files.newBufferedWriter(output,StandardOpenOption.CREATE_NEW)) {
            writer.write("timestampMs,bosses,ordinaryMobs,visibleBots,aliveBots,deadBots,observedMonsterHpLoss,observedBotHpLoss,observedBotDeaths,showStatus,incidentStatus\n");
            for(int second=0;second<300;second++) {
                int bosses=0,ordinary=0,visible=0,living=0,dead=0;
                for(Object monster:(Collection<?>)call(map,"getAllMonsters")) {
                    if(!(boolean)call(monster,"isAlive"))continue;
                    if((boolean)call(call(monster,"getStats"),"isBoss"))bosses++;else ordinary++;
                    int oid=(int)call(monster,"getObjectId");long hp=((Number)call(monster,"getHp")).longValue();
                    Long prior=monsterHp.put(oid,hp);if(prior!=null)observedDamage+=Math.max(0,prior-hp);
                }
                for(Object bot:(Collection<?>)call(map,"getCharacters")) {
                    if(!(boolean)isBot.invoke(null,bot))continue;visible++;
                    int id=(int)call(bot,"getId"),hp=(int)call(bot,"getHp");boolean lives=(boolean)call(bot,"isAlive");
                    if(lives)living++;else dead++;
                    Integer prior=botHp.put(id,hp);if(prior!=null)observedHpLoss+=Math.max(0,prior-hp);
                    Boolean previous=alive.put(id,lives);if(Boolean.TRUE.equals(previous)&&!lives)deaths++;
                }
                String status=String.valueOf(call(waves,"status",channel)).replace("\"","\"\"");
                String incidentStatus=String.valueOf(call(incidents,"status",channel)).replace("\"","\"\"");
                writer.write(System.currentTimeMillis()+","+bosses+","+ordinary+","+visible+","+living+","+dead+","+observedDamage+","+observedHpLoss+","+deaths+",\""+status+"\",\""+incidentStatus+"\"\n");writer.flush();
                Thread.sleep(1000);
            }
        } catch(Exception failure) {System.err.println("Finite managed wave observer failed: "+failure.getClass().getSimpleName());}
    }
    private static Object call(Object target,String method,Object... args)throws Exception {
        for(Method m:target.getClass().getMethods())if(m.getName().equals(method)&&m.getParameterCount()==args.length)
            try{return m.invoke(target,args);}catch(IllegalArgumentException wrongOverload){}
        throw new NoSuchMethodException(method);
    }
}

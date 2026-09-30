package gmevents;

import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Read-only, finite live JVM baseline/census probe. No commands, actor mutation or population creation. */
public final class EventProbe {
    public static void agentmain(String arguments,Instrumentation instrumentation) throws Exception {
        String[] args=arguments.split(",");String name=args[0];int seconds=args.length>1?Integer.parseInt(args[1]):60;
        if(!name.matches("[a-zA-Z0-9._-]+") || seconds<1 || seconds>3600) throw new IllegalArgumentException("report basename, seconds 1..3600");
        Path folder=Path.of("logs/event-capacity");Files.createDirectories(folder);
        Map<String,Object> census=census(instrumentation);
        census.put("capturedUtc",java.time.Instant.now().toString());census.put("kind","baseline-census");
        census.put("capacityVerified",false);census.put("renderClientsVerified",0);
        census.put("javaVersion",System.getProperty("java.version"));census.put("os",System.getProperty("os.name"));
        census.put("processors",Runtime.getRuntime().availableProcessors());
        Files.writeString(folder.resolve(name+".json"),json(census),StandardOpenOption.CREATE_NEW);
        Thread sampler=new Thread(()->sample(folder.resolve(name+".csv"),seconds),"gm-events-baseline-probe");sampler.setDaemon(true);sampler.start();
    }
    private static Object call(Object object,String method,Object... args) throws ReflectiveOperationException {
        for(Method m:object.getClass().getMethods()) if(m.getName().equals(method) && m.getParameterCount()==args.length)
            try {return m.invoke(object,args);} catch(IllegalArgumentException wrongOverload) { }
        throw new NoSuchMethodException(method);
    }
    private static Map<String,Object> census(Instrumentation instrumentation) throws Exception {
        Class<?> storage=null;for(Class<?> type:instrumentation.getAllLoadedClasses())
            if(type.getName().equals("soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage")) {storage=type;break;}
        Map<String,Object> result=new LinkedHashMap<>();
        if(storage==null) {result.put("censusAvailable",false);return result;}
        Map<?,?> registry=(Map<?,?>)storage.getMethod("getAllBots").invoke(null);
        List<?> snapshot=new ArrayList<>(registry.values());Map<String,Integer> bands=new TreeMap<>(),roles=new TreeMap<>(),states=new TreeMap<>();
        Map<Object,Set<Object>> presence=new IdentityHashMap<>();int registered=0,live=0,running=0,alive=0,potential=0,withHpStock=0;
        for(Object bot:snapshot) {
            registered++;
            try {
                Object actor=call(bot,"getChr"),map=call(actor,"getMap");if(map==null) continue;
                Set<Object> occupants=presence.get(map);
                if(occupants==null) {occupants=Collections.newSetFromMap(new IdentityHashMap<>());occupants.addAll((Collection<?>)call(map,"getCharacters"));presence.put(map,occupants);}
                if(!occupants.contains(actor)) continue;live++;
                boolean isRunning=(boolean)call(bot,"getRunning"),isAlive=(boolean)call(actor,"isAlive");
                if(isRunning) running++;if(isAlive) alive++;
                String role=bot.getClass().getSimpleName(),state=String.valueOf(call(bot,"getState"));roles.merge(role,1,Integer::sum);states.merge(state,1,Integer::sum);
                int world=(int)call(actor,"getWorld"),mapId=(int)call(actor,"getMapId"),level=(int)call(actor,"getLevel");
                int channel=(int)call(call(actor,"getClient"),"getChannel"),job=(int)call(call(actor,"getJob"),"getId");
                bands.merge(world+":"+channel+":"+mapId+":L"+(level/10*10)+":job"+job,1,Integer::sum);
                boolean available=Set.of("SocialBot","TrainingBot","TownWandererBot").contains(role) && isRunning && isAlive
                    && call(actor,"getTrade")==null && call(actor,"getParty")==null && call(actor,"getEventInstance")==null && call(actor,"getPlayerShop")==null;
                if(available) potential++;
                @SuppressWarnings({"unchecked","rawtypes"}) Object use=Enum.valueOf((Class)Class.forName("client.inventory.InventoryType",false,actor.getClass().getClassLoader()),"USE");
                Collection<?> items=(Collection<?>)call(call(actor,"getInventory",use),"list");
                if(items.stream().anyMatch(item->{try {return (int)call(item,"getItemId")/10000==200 && ((Number)call(item,"getQuantity")).intValue()>0;} catch(Exception e) {return false;}})) withHpStock++;
            } catch(ReflectiveOperationException mismatch) {result.merge("unreadableActors",1,(a,b)->(int)a+(int)b);}
        }
        result.put("censusAvailable",true);result.put("registeredBots",registered);result.put("liveBots",live);result.put("runningBots",running);result.put("aliveBots",alive);
        result.put("potentialBeforeTaskAndDialogueChecks",potential);result.put("actorsWithPotionItems",withHpStock);
        result.put("worldChannelMapLevelJobCounts",bands);result.put("roles",roles);result.put("states",states);
        return result;
    }
    private static void sample(Path output,int seconds) {
        var os=ManagementFactory.getOperatingSystemMXBean();var heap=ManagementFactory.getMemoryMXBean();var threads=ManagementFactory.getThreadMXBean();
        long priorTime=System.nanoTime(),priorCpu=os instanceof com.sun.management.OperatingSystemMXBean extended?extended.getProcessCpuTime():-1;
        try(var writer=Files.newBufferedWriter(output,StandardOpenOption.CREATE_NEW)) {
            writer.write("timestampMs,cpuPercent,heapUsed,heapCommitted,heapMax,platformThreads,gcCount,gcTimeMs\n");
            for(int i=0;i<seconds;i++) {
                Thread.sleep(1000);long now=System.nanoTime(),cpu=os instanceof com.sun.management.OperatingSystemMXBean extended?extended.getProcessCpuTime():-1;
                double percent=cpu<0 || priorCpu<0?Double.NaN:(cpu-priorCpu)*100.0/Math.max(1,(now-priorTime)*Runtime.getRuntime().availableProcessors());
                var memory=heap.getHeapMemoryUsage();long gcCount=0,gcTime=0;
                for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) {gcCount+=Math.max(0,gc.getCollectionCount());gcTime+=Math.max(0,gc.getCollectionTime());}
                writer.write(System.currentTimeMillis()+","+percent+","+memory.getUsed()+","+memory.getCommitted()+","+memory.getMax()+","+threads.getThreadCount()+","+gcCount+","+gcTime+"\n");writer.flush();
                priorTime=now;priorCpu=cpu;
            }
        } catch(Exception failure) {System.err.println("GM event baseline probe failed: "+failure.getClass().getSimpleName());}
    }
    private static String json(Object value) {
        if(value==null) return "null";
        if(value instanceof Number || value instanceof Boolean) return value.toString();
        if(value instanceof Map<?,?> map) {StringJoiner out=new StringJoiner(",","{","}");map.forEach((k,v)->out.add(json(k.toString())+":"+json(v)));return out.toString();}
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
}

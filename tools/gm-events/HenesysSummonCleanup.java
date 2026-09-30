package gmevents;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** Remove only the exact native objects recorded by the failed human trial. No legitimate kills/rewards. */
public final class HenesysSummonCleanup {
    public static void agentmain(String args, Instrumentation instrumentation) throws Exception {
        if (!"henesys-human-20260930-02".equals(args)) throw new IllegalArgumentException("Exact owned trial required");
        Class<?> serverClass = Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(c -> c.getName().equals("net.server.Server")).findFirst().orElseThrow();
        Object server = serverClass.getMethod("getInstance").invoke(null);
        Object map = call(call(call(server, "getChannel", 0, 1), "getMapFactory"), "getMap", 100000000);
        int removed = 0;
        for (String row : Files.readAllLines(Path.of("logs/event-capacity/" + args + ".csv"))) {
            if (row.startsWith("#") || row.startsWith("template")) continue;
            String[] columns = row.split(",");
            int template = Integer.parseInt(columns[0]), oid = Integer.parseInt(columns[1]);
            if (!Set.of(6130101, 8130100, 8150000).contains(template)) throw new IllegalStateException("Unexpected owned template");
            Object monster = call(map, "getMonsterByOid", oid);
            if (monster == null || (int)call(monster, "getId") != template) continue;
            call(map, "killMonster", monster, null, false, 1, (short)0); removed++;
        }
        Files.writeString(Path.of("logs/event-capacity/" + args + "-cleanup.txt"), "ownedRemoved=" + removed + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("Henesys owned trial cleanup removed=" + removed);
    }
    private static Object call(Object target, String method, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) if (m.getName().equals(method) && m.getParameterCount() == args.length)
            try { return m.invoke(target, args); } catch (IllegalArgumentException wrongOverload) { }
        throw new NoSuchMethodException(method);
    }
}

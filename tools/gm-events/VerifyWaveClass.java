package gmevents;

/** Link the exact packaged class under JVM bytecode verification without starting the server. */
public final class VerifyWaveClass {
    public static void main(String[] args) throws Exception {
        var type = Class.forName("server.events.gm.WaveInvasionService", false,
                ClassLoader.getSystemClassLoader());
        if (type.getDeclaredMethod("startHenesys", net.server.channel.Channel.class) == null)
            throw new AssertionError("missing operator entry");
        System.out.println("wave_class_linked=" + type.getName());
    }
}

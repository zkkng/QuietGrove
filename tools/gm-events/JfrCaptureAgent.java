package gmevents;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

/** Finite, operator-attached JFR capture for one pinned game JVM. */
public final class JfrCaptureAgent {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private JfrCaptureAgent() { }

    public static void agentmain(String argument, Instrumentation instrumentation) throws Exception {
        String[] args = argument.split(",", -1);
        if (args.length != 2 || !args[0].matches("[A-Za-z0-9._-]{1,80}"))
            throw new IllegalArgumentException("basename,seconds");
        int seconds = Integer.parseInt(args[1]);
        if (seconds < 10 || seconds > 7200)
            throw new IllegalArgumentException("duration must be 10..7200 seconds");
        if (!ACTIVE.compareAndSet(false, true))
            throw new IllegalStateException("Finite JFR capture already active");
        try {
            if (!FlightRecorder.getFlightRecorder().getRecordings().isEmpty())
                throw new IllegalStateException("Another JFR recording is active");
            Path directory = Path.of("/opt/solomapling/logs/gm-events");
            Files.createDirectories(directory);
            Path output = directory.resolve(args[0] + ".jfr");
            if (Files.exists(output)) throw new IllegalStateException("JFR output already exists");
            Recording recording = new Recording(Configuration.getConfiguration("profile"));
            recording.setName(args[0]);
            recording.setToDisk(true);
            Thread worker = new Thread(() -> {
                try (recording) {
                    recording.start();
                    Thread.sleep(Duration.ofSeconds(seconds).toMillis());
                    recording.stop();
                    recording.dump(output);
                } catch (Exception failure) {
                    System.err.println("Finite GM JFR capture failed: " + failure);
                    try {
                        Files.writeString(directory.resolve(args[0] + ".error.txt"),
                                Instant.now() + " " + failure + "\n", StandardOpenOption.CREATE_NEW);
                    } catch (Exception ignored) { }
                } finally {
                    ACTIVE.set(false);
                }
            }, "gm-event-finite-jfr");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception failure) {
            ACTIVE.set(false);
            throw failure;
        }
    }
}

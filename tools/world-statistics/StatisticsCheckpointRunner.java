package server.statistics.checkpoint;

import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.io.PrintWriter;
import java.nio.file.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

public final class StatisticsCheckpointRunner {
    public static void main(String[] args) throws Exception {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectPackage("server.statistics")).build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create(); launcher.registerTestExecutionListeners(listener); launcher.execute(request);
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.out));
        if (args.length==1) Files.writeString(Path.of(args[0]),"{\"found\":"+summary.getTestsFoundCount()
                +",\"succeeded\":"+summary.getTestsSucceededCount()+",\"failed\":"+summary.getTestsFailedCount()
                +",\"skipped\":"+summary.getTestsSkippedCount()+"}\n");
        if (summary.getTestsFoundCount()==0 || summary.getTestsFailedCount()!=0 || summary.getTestsSkippedCount()!=0)
            System.exit(1);
    }
}

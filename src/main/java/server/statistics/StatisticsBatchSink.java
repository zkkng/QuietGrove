package server.statistics;

import java.io.IOException;

@FunctionalInterface
public interface StatisticsBatchSink {
    /** True for newly committed batch, false for an identical durable replay. */
    boolean apply(StatisticsBatch batch) throws IOException;
}

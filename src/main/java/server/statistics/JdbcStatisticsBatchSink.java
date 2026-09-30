package server.statistics;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;

/** Background-only. Caller MUST supply a dedicated bounded statistics DataSource. */
public final class JdbcStatisticsBatchSink implements StatisticsBatchSink {
    private final DataSource dataSource;
    private final int queryTimeoutSeconds;
    public JdbcStatisticsBatchSink(DataSource dataSource, int queryTimeoutSeconds) {
        if (dataSource == null || queryTimeoutSeconds < 1 || queryTimeoutSeconds > 30)
            throw new IllegalArgumentException("Dedicated data source and bounded query timeout required");
        this.dataSource = dataSource; this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override public boolean apply(StatisticsBatch batch) throws IOException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement receipt = connection.prepareStatement(
                        "INSERT INTO stats_batch_receipt (epoch_id,producer_id,boot_id,batch_sequence,checksum,first_at,last_at) VALUES (?,?,?,?,?,?,?)")) {
                    receipt.setQueryTimeout(queryTimeoutSeconds);
                    identity(receipt, batch);
                    receipt.setString(5, batch.checksum()); receipt.setLong(6, batch.firstAt()); receipt.setLong(7, batch.lastAt());
                    receipt.executeUpdate();
                } catch (SQLException exception) {
                    if (exception.getErrorCode() != 1062) throw exception;
                    try (PreparedStatement existing = connection.prepareStatement(
                            "SELECT checksum FROM stats_batch_receipt WHERE epoch_id=? AND producer_id=? AND boot_id=? AND batch_sequence=? FOR UPDATE")) {
                        existing.setQueryTimeout(queryTimeoutSeconds); identity(existing, batch);
                        try (ResultSet row = existing.executeQuery()) {
                            if (!row.next() || !batch.checksum().equals(row.getString(1)))
                                throw new SQLException("Statistics batch identity/checksum conflict", "23000", exception);
                        }
                    }
                    connection.commit(); return false;
                }
                try (PreparedStatement aggregate = connection.prepareStatement(
                        "INSERT INTO stats_aggregate (epoch_id,definition_version,metric_id,projection_id,world_id,population_id,assistance_id,entity_id,region_id,reason_id,method_id,day_bucket,value) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE value=value+VALUES(value)")) {
                    aggregate.setQueryTimeout(queryTimeoutSeconds);
                    for (var delta : batch.deltas()) {
                        var key = delta.key();
                        aggregate.setString(1, batch.epoch()); aggregate.setInt(2, 1);
                        aggregate.setInt(3, key.metric()); aggregate.setInt(4, key.projection());
                        aggregate.setInt(5, key.world()); aggregate.setInt(6, key.population());
                        aggregate.setInt(7, key.assistance()); aggregate.setInt(8, key.entity());
                        aggregate.setInt(9, key.region()); aggregate.setInt(10, key.reason());
                        aggregate.setInt(11, key.method()); aggregate.setLong(12, key.day());
                        aggregate.setBigDecimal(13, new BigDecimal(delta.value()));
                        aggregate.addBatch();
                    }
                    int[] results = aggregate.executeBatch();
                    if (results.length != batch.deltas().size()) throw new SQLException("Incomplete statistics batch result");
                    for (int result : results) if (result == Statement.EXECUTE_FAILED) throw new SQLException("Statistics delta execution failed");
                }
                connection.commit(); return true;
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        } catch (SQLException exception) { throw new IOException("Statistics batch transaction failed", exception); }
    }

    private static void identity(PreparedStatement statement, StatisticsBatch batch) throws SQLException {
        statement.setString(1, batch.epoch()); statement.setString(2, batch.producer().toString());
        statement.setString(3, batch.boot().toString()); statement.setLong(4, batch.sequence());
    }
}

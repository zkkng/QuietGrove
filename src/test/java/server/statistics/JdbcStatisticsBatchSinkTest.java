package server.statistics;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcStatisticsBatchSinkTest {
    private static final class Database implements InvocationHandler {
        boolean duplicate, failAggregate, failCommit, silentBatchFailure, committed, rolledBack;
        String checksum;
        int aggregateExecutions, receiptExecutions, rows, timeouts;
        final List<String> order = new ArrayList<>();
        final DataSource source = proxy(DataSource.class, (object,method,args)-> {
            if (method.getName().equals("getConnection")) return proxy(Connection.class,this);
            return defaultValue(method.getReturnType());
        });
        @Override public Object invoke(Object object, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "prepareStatement" -> statement((String)args[0]);
                case "commit" -> {
                    order.add("commit"); if (failCommit) throw new SQLException("Commit unavailable");
                    committed=true; yield null;
                }
                case "rollback" -> {order.add("rollback"); rolledBack=true; yield null;}
                default -> defaultValue(method.getReturnType());
            };
        }
        private PreparedStatement statement(String sql) {
            boolean receipt = sql.startsWith("INSERT INTO stats_batch_receipt");
            boolean read = sql.startsWith("SELECT checksum");
            return proxy(PreparedStatement.class,(object,method,args)-> {
                return switch (method.getName()) {
                    case "setQueryTimeout" -> {assertEquals(5,args[0]); timeouts++; yield null;}
                    case "executeUpdate" -> {
                        assertTrue(receipt); receiptExecutions++; order.add("receipt");
                        if (duplicate) throw new SQLException("Duplicate","23000",1062); yield 1;
                    }
                    case "executeQuery" -> {
                        assertTrue(read); boolean[] next={true};
                        yield proxy(ResultSet.class,(row,rowMethod,rowArgs)->switch (rowMethod.getName()) {
                            case "next" -> {boolean result=next[0];next[0]=false;yield result;}
                            case "getString" -> checksum;
                            default -> defaultValue(rowMethod.getReturnType());
                        });
                    }
                    case "addBatch" -> {rows++;yield null;}
                    case "executeBatch" -> {
                        aggregateExecutions++; order.add("aggregate");
                        if (failAggregate) throw new SQLException("Aggregate rejected");
                        int[] result=new int[rows];Arrays.fill(result,silentBatchFailure ? Statement.EXECUTE_FAILED : 1);yield result;
                    }
                    default -> defaultValue(method.getReturnType());
                };
            });
        }
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type==void.class) return null;
        if (type==boolean.class) return false;
        if (type==int.class) return 0;
        if (type==long.class) return 0L;
        throw new UnsupportedOperationException(type.toString());
    }
    @Test void receiptAndAllDeltasCommitInOneTransactionWithBoundedStatements() throws Exception {
        var database = new Database();
        assertTrue(new JdbcStatisticsBatchSink(database.source,5).apply(StatisticsJournalTest.batch(1)));
        assertEquals(List.of("receipt","aggregate","commit"),database.order);
        assertEquals(1,database.rows); assertEquals(2,database.timeouts);
        assertTrue(database.committed); assertFalse(database.rolledBack);
    }
    @Test void identicalReceiptRetryDoesNotApplyAggregateAgain() throws Exception {
        var database = new Database(); database.duplicate=true; database.checksum=StatisticsJournalTest.batch(1).checksum();
        assertFalse(new JdbcStatisticsBatchSink(database.source,5).apply(StatisticsJournalTest.batch(1)));
        assertEquals(0,database.aggregateExecutions); assertTrue(database.committed);
    }
    @Test void reusedIdentityWithDifferentChecksumRollsBackAndCannotIncrement() {
        var database = new Database(); database.duplicate=true; database.checksum="different";
        assertThrows(IOException.class,()->new JdbcStatisticsBatchSink(database.source,5).apply(StatisticsJournalTest.batch(1)));
        assertTrue(database.rolledBack); assertFalse(database.committed); assertEquals(0,database.aggregateExecutions);
    }
    @Test void failedAggregateOrCommitRollsBackReceiptAsWell() {
        for (boolean failAggregate : List.of(true,false)) {
            var database = new Database(); database.failAggregate=failAggregate; database.failCommit=!failAggregate;
            assertThrows(IOException.class,()->new JdbcStatisticsBatchSink(database.source,5).apply(StatisticsJournalTest.batch(1)));
            assertTrue(database.rolledBack); assertFalse(database.committed);
        }
    }
    @Test void failedBatchResultWithoutExceptionStillRollsBackReceipt() {
        var database=new Database(); database.silentBatchFailure=true;
        assertThrows(IOException.class,()->new JdbcStatisticsBatchSink(database.source,5).apply(StatisticsJournalTest.batch(1)));
        assertTrue(database.rolledBack); assertFalse(database.committed);
    }}

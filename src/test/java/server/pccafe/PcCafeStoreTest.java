package server.pccafe;

import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PcCafeStoreTest {
    @Test void sqlReadFailurePropagatesRatherThanCreatingAnEmptyBalance() throws Exception {
        var con = mock(Connection.class); when(con.prepareStatement(anyString())).thenThrow(new SQLException("offline"));
        assertThrows(SQLException.class, () -> PcCafeStore.load(con, 42));
    }
    @Test void roundTripUsesTheCallerTransactionAndNeverCommitsIndependently() throws Exception {
        var con = mock(Connection.class); var ps = mock(PreparedStatement.class); var rs = mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenReturn(ps); when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true); var state = new PcCafeState(); state.credit(37);
        when(rs.getString(1)).thenReturn(state.encode());
        var restored = PcCafeStore.load(con, 42); assertEquals(37, restored.balance());
        PcCafeStore.save(con, 42, restored); verify(ps).setString(2, state.encode()); verify(ps).executeUpdate();
        verify(con, never()).commit(); verify(con, never()).setAutoCommit(anyBoolean()); verify(con, never()).close();
    }
    @Test void corruptRowAndFailedWritePropagate() throws Exception {
        var con = mock(Connection.class); var ps = mock(PreparedStatement.class); var rs = mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenReturn(ps); when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true); when(rs.getString(1)).thenReturn("corrupt");
        assertThrows(SQLException.class, () -> PcCafeStore.load(con, 42));
        when(ps.executeUpdate()).thenThrow(new SQLException("disk full"));
        assertThrows(SQLException.class, () -> PcCafeStore.save(con, 42, new PcCafeState()));
    }
}

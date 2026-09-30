package net.server.task;

import client.Family;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.mockito.Mockito.*;

class FamilyDailyResetTaskTest {
    @Test void failedEntitlementDeleteRollsBackWithoutClearingMemory() throws Exception {
        World world = mock(World.class);
        Family family = mock(Family.class);
        when(world.getFamilies()).thenReturn(List.of(family));
        Connection con = mock(Connection.class);
        PreparedStatement rep = mock(PreparedStatement.class), uses = mock(PreparedStatement.class);
        when(con.getAutoCommit()).thenReturn(true);
        when(con.prepareStatement(anyString())).thenReturn(rep, uses);
        when(uses.executeUpdate()).thenThrow(new SQLException("delete failed"));
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenReturn(con);
            new FamilyDailyResetTask(world).run();
        }
        verify(con).rollback();
        verify(con, never()).commit();
        verify(family, never()).resetDailyReps();
    }

    @Test void successfulResetCommitsBeforeClearingMemory() throws Exception {
        World world = mock(World.class);
        Family family = mock(Family.class);
        when(world.getFamilies()).thenReturn(List.of(family));
        Connection con = mock(Connection.class);
        PreparedStatement rep = mock(PreparedStatement.class), uses = mock(PreparedStatement.class);
        when(con.getAutoCommit()).thenReturn(true);
        when(con.prepareStatement(anyString())).thenReturn(rep, uses);
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenReturn(con);
            new FamilyDailyResetTask(world).run();
        }
        var order = inOrder(con, family);
        order.verify(con).commit();
        order.verify(family).resetDailyReps();
    }
}

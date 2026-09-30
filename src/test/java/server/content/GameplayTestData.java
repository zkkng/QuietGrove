package server.content;

import server.ItemInformationProvider;
import tools.DatabaseConnection;
import java.sql.Connection;
import static org.mockito.Mockito.*;

/** Reads actual WZ data; only the startup card-to-monster SQL index is empty in these offline tests. */
final class GameplayTestData {
    static void initializeItems() throws Exception {
        Connection connection = mock(Connection.class, RETURNS_DEEP_STUBS);
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            ItemInformationProvider.getInstance();
        }
    }
}

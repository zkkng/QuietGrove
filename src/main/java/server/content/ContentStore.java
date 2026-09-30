package server.content;

import java.sql.Connection;
import java.sql.SQLException;

public final class ContentStore {
    private ContentStore() {}
    public static ContentState load(Connection con, int character) throws SQLException {
        try (var ps = con.prepareStatement("SELECT state FROM character_content WHERE characterid = ?")) {
            ps.setInt(1, character);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return new ContentState();
                try { return ContentState.decode(rs.getString(1)); }
                catch (RuntimeException e) { throw new SQLException("Invalid content progress for " + character, e); }
            }
        }
    }
    public static void save(Connection con, int character, ContentState state) throws SQLException {
        try (var ps = con.prepareStatement("INSERT INTO character_content (characterid,state) VALUES (?,?) ON DUPLICATE KEY UPDATE state=VALUES(state)")) {
            ps.setInt(1, character); ps.setString(2, state.encode()); ps.executeUpdate();
        }
    }
}

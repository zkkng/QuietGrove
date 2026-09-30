package server.pccafe;

import java.sql.Connection;
import java.sql.SQLException;

/** Uses the character save transaction, so rewards and their coin cost commit together. */
public final class PcCafeStore {
    private PcCafeStore() {}
    public static PcCafeState load(Connection con, int character) throws SQLException {
        try (var ps = con.prepareStatement("SELECT state FROM pc_cafe_progress WHERE characterid = ?")) {
            ps.setInt(1, character);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return new PcCafeState();
                try { return PcCafeState.decode(rs.getString(1)); }
                catch (RuntimeException e) { throw new SQLException("Invalid cafe data for character " + character, e); }
            }
        }
    }
    public static void save(Connection con, int character, PcCafeState state) throws SQLException {
        try (var ps = con.prepareStatement("INSERT INTO pc_cafe_progress (characterid, state) VALUES (?, ?) ON DUPLICATE KEY UPDATE state = VALUES(state)")) {
            ps.setInt(1, character); ps.setString(2, state.encode()); ps.executeUpdate();
        }
    }
}

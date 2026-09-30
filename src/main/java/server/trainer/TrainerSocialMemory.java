package server.trainer;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Durable, observer-scoped roleplay memory. It never writes enforcement state. */
public final class TrainerSocialMemory {
    private static final Logger log = LoggerFactory.getLogger(TrainerSocialMemory.class);
    private static final long ORDINARY_TTL_MS = 2 * 60 * 60 * 1000L;
    private static final long LOSS_TTL_MS = 7 * 24 * 60 * 60 * 1000L;

    private TrainerSocialMemory() { }

    public static void observed(Character observer, Character actor, TrainerReactionPolicy.Kind kind,
                                boolean directHighValueLoss, int itemId) {
        if (observer == null || actor == null || kind == null || observer == actor) return;
        int evidence = directHighValueLoss ? 80 : switch (kind) {
            case FMA, VAC -> 18;
            case FLIGHT -> 12;
            case LOOT -> 15;
        };
        record(UUID.randomUUID(), observer.getName(), actor.getId(), kind.name(), evidence,
                directHighValueLoss ? 1 : 0, itemId, System.currentTimeMillis());
    }

    public static void unknownLoss(Character observer, int itemId) {
        if (observer == null) return;
        record(UUID.randomUUID(), observer.getName(), null, "UNKNOWN_LOSS", 0, 1,
                itemId, System.currentTimeMillis());
    }

    /** One committed loss, even when conversation is on cooldown or the actor immediately leaves. */
    public static void lostItem(UUID eventId, Character observer, Character actor, int itemId,
                                boolean sawActor, boolean remote, boolean highValue) {
        if (eventId == null || observer == null) return;
        UUID receipt = UUID.nameUUIDFromBytes((eventId + ":loss:" + observer.getName())
                .getBytes(StandardCharsets.UTF_8));
        Integer culprit = sawActor && actor != null ? actor.getId() : null;
        int evidence = culprit == null ? 0 : remote ? (highValue ? 80 : 40) : 20;
        record(receipt, observer.getName(), culprit,
                culprit == null ? "UNKNOWN_LOSS" : remote ? "REMOTE_LOSS" : "NEARBY_LOSS",
                evidence, 1, itemId, System.currentTimeMillis());
    }

    static void record(UUID incidentId, String botName, Integer actorId, String kind,
                       int evidence, int losses, int itemId, long now) {
        if (incidentId == null || botName == null || botName.isBlank() || botName.length() > 32
                || kind == null || kind.isBlank() || kind.length() > 24 || evidence < 0
                || evidence > 100 || losses < 0 || losses > 1 || now <= 0) return;
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try { write(connection, incidentId, botName, actorId, kind, evidence, losses, itemId, now); connection.commit(); }
            catch (SQLException failure) { connection.rollback(); throw failure; }
        } catch (SQLException | IllegalStateException failure) {
            log.warn("Could not persist trainer social observation bot={} actor={} kind={}", botName, actorId, kind, failure);
        }
    }

    /** All witnesses from one observed event use one connection and one transaction. */
    static void observedAll(java.util.List<Character> witnesses, Character actor, TrainerReactionPolicy.Kind kind, int itemId) {
        if (actor == null || witnesses.isEmpty()) return;
        int evidence = switch (kind) { case FMA, VAC -> 18; case FLIGHT -> 12; case LOOT -> 15; };
        long now = System.currentTimeMillis();
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (Character observer : witnesses.stream().limit(30).toList())
                    write(connection, UUID.randomUUID(), observer.getName(), actor.getId(), kind.name(), evidence, 0, itemId, now);
                connection.commit();
            } catch (SQLException failure) { connection.rollback(); throw failure; }
        } catch (SQLException | IllegalStateException failure) {
            log.warn("Could not persist batched trainer witnesses actor={} count={}", actor.getId(), witnesses.size(), failure);
        }
    }

    /** The actual prize transaction also commits the owner's observed loss, closing the crash gap. */
    static void committedLoss(Connection connection, UUID event, String owner, Integer actor, int itemId,
                              boolean remote, boolean highValue) throws SQLException {
        UUID receipt = UUID.nameUUIDFromBytes((event + ":loss:" + owner).getBytes(StandardCharsets.UTF_8));
        write(connection, receipt, owner, actor, actor == null ? "UNKNOWN_LOSS" : remote ? "REMOTE_LOSS" : "NEARBY_LOSS",
                actor == null ? 0 : remote ? (highValue ? 80 : 40) : 20, 1, itemId, System.currentTimeMillis());
    }

    private static void write(Connection connection, UUID incidentId, String botName, Integer actorId, String kind,
                              int evidence, int losses, int itemId, long now) throws SQLException {
        long expiry = now + (losses > 0 ? LOSS_TTL_MS : ORDINARY_TTL_MS);
        try (PreparedStatement incident = connection.prepareStatement(
                "INSERT IGNORE INTO trainer_social_incidents "
                        + "(incident_id, bot_name, actor_character_id, kind, evidence, item_id, observed_at_ms, expires_at_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            incident.setString(1, incidentId.toString());
            incident.setString(2, botName);
            if (actorId == null) incident.setNull(3, java.sql.Types.INTEGER);
            else incident.setInt(3, actorId);
            incident.setString(4, kind);
            incident.setInt(5, evidence);
            if (itemId <= 0) incident.setNull(6, java.sql.Types.INTEGER);
            else incident.setInt(6, itemId);
            incident.setLong(7, now);
            incident.setLong(8, expiry);
            if (incident.executeUpdate() == 0) return;
        }
        if (actorId != null && actorId > 0) {
            try (PreparedStatement summary = connection.prepareStatement(
                    "INSERT INTO trainer_social_memory "
                            + "(bot_name, actor_character_id, suspicion, losses, first_observed_at_ms, "
                            + "last_observed_at_ms, expires_at_ms, last_kind) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE suspicion = LEAST(100, "
                            + "IF(expires_at_ms < VALUES(last_observed_at_ms), 0, suspicion) + VALUES(suspicion)), "
                            + "losses = LEAST(1000, IF(expires_at_ms < VALUES(last_observed_at_ms), 0, losses) + VALUES(losses)), "
                            + "last_observed_at_ms = VALUES(last_observed_at_ms), "
                            + "expires_at_ms = GREATEST(expires_at_ms, VALUES(expires_at_ms)), "
                            + "last_kind = VALUES(last_kind)")) {
                summary.setString(1, botName);
                summary.setInt(2, actorId);
                summary.setInt(3, evidence);
                summary.setInt(4, losses);
                summary.setLong(5, now);
                summary.setLong(6, now);
                summary.setLong(7, expiry);
                summary.setString(8, kind);
                summary.executeUpdate();
            }
        }
    }

    /** Authenticated actor's attributed, unexpired observations only. No unknown-loss inference. */
    static java.util.Map<String,String> recent(int actorId,long now) {
        var result=new java.util.LinkedHashMap<String,String>();
        result.put("protocol","SoloMapling-Trainer-v3"); result.put("observationCount","0");
        try (Connection connection=DatabaseConnection.getConnection(); PreparedStatement query=connection.prepareStatement(
                "SELECT i.bot_name,i.kind,i.evidence,i.item_id,i.observed_at_ms,"
                + "COALESCE(m.suspicion,0),COALESCE(m.losses,0),COALESCE(m.expires_at_ms,0) "
                + "FROM trainer_social_incidents i LEFT JOIN trainer_social_memory m "
                + "ON m.bot_name=i.bot_name AND m.actor_character_id=i.actor_character_id "
                + "WHERE i.actor_character_id=? AND i.expires_at_ms>? ORDER BY i.observed_at_ms DESC,i.incident_id DESC LIMIT 12")) {
            query.setQueryTimeout(2); query.setInt(1,actorId); query.setLong(2,now);
            int count=0;
            try (ResultSet rows=query.executeQuery()) { while (rows.next()) {
                boolean active=rows.getLong(8)>now; int suspicion=active?rows.getInt(6):0;
                result.put("observation"+count++,rows.getLong(5)+" | "+rows.getString(1)+" | "+rows.getString(2)
                        +" | evidence +"+rows.getInt(3)+" | current suspicion "+suspicion
                        +" | item "+rows.getInt(4)+" | "+(suspicion>=75&&rows.getInt(7)>0?"later invitations refused":"memory only"));
            }}
            result.put("observationCount",Integer.toString(count)); result.put("observationStatus","Current memory state; evidence scores are roleplay signals, not probabilities. Unknown losses are not attributed to you.");
        } catch (SQLException | IllegalStateException failure) {
            result.put("observationStatus","Observation store unavailable; powers unchanged");
            log.warn("Trainer observation read unavailable actor={}",actorId,failure);
        }
        return result;
    }

    /** Only a strong direct memory affects a later invitation; expired memories cannot refuse. */
    public static boolean refuses(Character observer, Character actor) {
        if (observer == null || actor == null) return false;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT suspicion, losses FROM trainer_social_memory "
                             + "WHERE bot_name = ? AND actor_character_id = ? AND expires_at_ms > ?")) {
            query.setQueryTimeout(2);
            query.setString(1, observer.getName());
            query.setInt(2, actor.getId());
            query.setLong(3, System.currentTimeMillis());
            try (ResultSet result = query.executeQuery()) {
                return result.next() && result.getInt(1) >= 75 && result.getInt(2) > 0;
            }
        } catch (SQLException | IllegalStateException failure) {
            log.warn("Could not read trainer social memory bot={} actor={}",
                    observer.getName(), actor.getId(), failure);
            return false;
        }
    }
}

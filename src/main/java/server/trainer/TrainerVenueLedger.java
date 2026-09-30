package server.trainer;

import client.Character;
import client.inventory.Item;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** Finite, identity-bearing venue stock. All transitions are durable and idempotent. */
public final class TrainerVenueLedger {
    public record Asset(UUID id, int mapId, int channelId, String ownerBotName, Item item,
                        long estimatedValue) { }
    private static final int MAX_DAILY_UNITS = 40;
    private static final long MAX_DAILY_VALUE = 100_000_000L;

    private TrainerVenueLedger() { }

    public static boolean isVenue(int mapId) {
        return mapId == 105040401 || mapId == 100000102;
    }

    public static boolean isPaidRound(UUID id) throws SQLException {
        try (Connection c = DatabaseConnection.getConnection()) { return isPaidRound(c, id); }
    }

    static boolean isPaidRound(Connection c, UUID id) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT tier_mesos FROM trainer_venue_rounds WHERE round_id = ?")) {
            q.setString(1, id.toString());
            try (ResultSet rows = q.executeQuery()) {
                if (!rows.next()) throw new SQLException("Prize round missing");
                return rows.getInt(1) > 0;
            }
        }
    }

    static boolean hasPersonalGrant(int mapId, int channel, String owner) throws SQLException {
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT COUNT(*) FROM trainer_venue_stock WHERE venue_map_id = ? AND channel_id = ? "
                        + "AND owner_bot_name = ? AND acquisition_reason = 'SOCIAL_CAMPAIGN_V1'")) {
            q.setInt(1, mapId); q.setInt(2, channel); q.setString(3, owner);
            try (ResultSet r = q.executeQuery()) {
                r.next(); int count = r.getInt(1);
                if (count != 0 && count != 3) throw new SQLException("Incomplete personal campaign grant");
                return count == 3; // Includes spent assets: depletion never causes another grant.
            }
        }
    }

    /** Three explicitly budgeted personal assets once per stable town identity, all in one commit. */
    static void grantPersonalStock(int mapId, int channel, String owner, List<Item> items,
                                   List<Long> values) throws SQLException {
        if (!TrainerSocialStock.eligible(owner, mapId, channel) || items.size() != 3 || values.size() != 3)
            throw new IllegalArgumentException("personal grant identity/size");
        int[] allowed = TrainerSocialStock.itemIds(owner);
        long total = 0;
        List<byte[]> snapshots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if (items.get(i).getItemId() != allowed[i] || values.get(i) <= 0 || values.get(i) > 60_000_000L)
                throw new IllegalArgumentException("personal grant content/value");
            total += values.get(i); snapshots.add(TrainerVenueItemCodec.encode(items.get(i)));
        }
        if (total > 60_000_000L) throw new IllegalArgumentException("personal campaign budget");
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (int i = 0; i < 3; i++) {
                    UUID asset = UUID.nameUUIDFromBytes((TrainerSocialStock.GRANT + ":" + owner + ":" + i)
                            .getBytes(StandardCharsets.US_ASCII));
                    UUID operation = stepId(asset, "personal-grant");
                    try (PreparedStatement insert = c.prepareStatement(
                            "INSERT IGNORE INTO trainer_venue_stock (asset_id, venue_map_id, channel_id, owner_bot_name, "
                                    + "item_id, item_snapshot, estimated_value, acquisition_operation_id, acquisition_reason, "
                                    + "state, acquired_at_ms, changed_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?)")) {
                        insert.setString(1, asset.toString()); insert.setInt(2, mapId); insert.setInt(3, channel);
                        insert.setString(4, owner); insert.setInt(5, items.get(i).getItemId());
                        insert.setBytes(6, snapshots.get(i)); insert.setLong(7, values.get(i));
                        insert.setString(8, operation.toString()); insert.setString(9, TrainerSocialStock.GRANT);
                        insert.setLong(10, System.currentTimeMillis()); insert.setLong(11, System.currentTimeMillis());
                        insert.executeUpdate();
                    }
                    if (!acquisitionExists(c, operation, asset, mapId, channel, owner, snapshots.get(i), values.get(i), TrainerSocialStock.GRANT))
                        throw new SQLException("Personal grant missing after insert");
                }
                c.commit();
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        }
    }

    /** Explicit daily budget issuance; duplicate operation IDs never mint stock twice. */
    public static boolean acquire(UUID assetId, UUID operationId, int mapId, int channelId,
                                  String ownerBotName, Item item, long estimatedValue,
                                  String acquisitionReason) throws SQLException {
        if (assetId == null || operationId == null || !isVenue(mapId) || channelId <= 0
                || ownerBotName == null || ownerBotName.isBlank() || ownerBotName.length() > 32
                || item == null || estimatedValue <= 0 || estimatedValue > MAX_DAILY_VALUE
                || acquisitionReason == null || acquisitionReason.isBlank()
                || acquisitionReason.length() > 64)
            throw new IllegalArgumentException("venue acquisition");
        byte[] snapshot = TrainerVenueItemCodec.encode(item);
        long now = System.currentTimeMillis();
        Date day = Date.valueOf(LocalDate.now(ZoneOffset.UTC));
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (acquisitionExists(connection, operationId, assetId, mapId, channelId,
                        ownerBotName, snapshot, estimatedValue, acquisitionReason)) {
                    connection.rollback(); return false;
                }
                try (PreparedStatement createBudget = connection.prepareStatement(
                        "INSERT IGNORE INTO trainer_venue_daily_budget "
                                + "(venue_map_id, channel_id, day_utc, issued_value, issued_units) "
                                + "VALUES (?, ?, ?, 0, 0)")) {
                    createBudget.setInt(1, mapId);
                    createBudget.setInt(2, channelId);
                    createBudget.setDate(3, day);
                    createBudget.executeUpdate();
                }
                try (PreparedStatement lockBudget = connection.prepareStatement(
                        "SELECT issued_value, issued_units FROM trainer_venue_daily_budget "
                                + "WHERE venue_map_id = ? AND channel_id = ? AND day_utc = ? FOR UPDATE")) {
                    lockBudget.setInt(1, mapId);
                    lockBudget.setInt(2, channelId);
                    lockBudget.setDate(3, day);
                    try (ResultSet rows = lockBudget.executeQuery()) {
                        if (!rows.next() || rows.getLong(1) < 0
                                || rows.getLong(1) > MAX_DAILY_VALUE - estimatedValue
                                || rows.getInt(2) >= MAX_DAILY_UNITS) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO trainer_venue_stock (asset_id, venue_map_id, channel_id, owner_bot_name, "
                                + "item_id, item_snapshot, estimated_value, acquisition_operation_id, "
                                + "acquisition_reason, state, acquired_at_ms, changed_at_ms) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?)")) {
                    insert.setString(1, assetId.toString());
                    insert.setInt(2, mapId);
                    insert.setInt(3, channelId);
                    insert.setString(4, ownerBotName);
                    insert.setInt(5, item.getItemId());
                    insert.setBytes(6, snapshot);
                    insert.setLong(7, estimatedValue);
                    insert.setString(8, operationId.toString());
                    insert.setString(9, acquisitionReason);
                    insert.setLong(10, now);
                    insert.setLong(11, now);
                    insert.executeUpdate();
                }
                try (PreparedStatement budget = connection.prepareStatement(
                        "UPDATE trainer_venue_daily_budget SET issued_value = issued_value + ?, "
                                + "issued_units = issued_units + 1 "
                                + "WHERE venue_map_id = ? AND channel_id = ? AND day_utc = ?")) {
                    budget.setLong(1, estimatedValue);
                    budget.setInt(2, mapId);
                    budget.setInt(3, channelId);
                    budget.setDate(4, day);
                    if (budget.executeUpdate() != 1) throw new SQLException("Venue budget disappeared");
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                if (failure instanceof SQLException &&
                        acquisitionExists(connection, operationId, assetId, mapId, channelId,
                                ownerBotName, snapshot, estimatedValue, acquisitionReason)) return false;
                throw failure;
            }
        }
    }

    private static boolean acquisitionExists(Connection connection, UUID operationId, UUID assetId,
                                               int mapId, int channelId, String owner, byte[] snapshot,
                                               long value, String reason) throws SQLException {
        try (PreparedStatement previous = connection.prepareStatement(
                "SELECT asset_id, venue_map_id, channel_id, item_snapshot, estimated_value, acquisition_reason "
                        + "FROM trainer_venue_stock WHERE acquisition_operation_id = ?")) {
            previous.setString(1, operationId.toString());
            try (ResultSet rows = previous.executeQuery()) {
                if (!rows.next()) return false;
                // Current owner may have changed through a legitimate bot claim.
                if (!assetId.toString().equals(rows.getString(1)) || mapId != rows.getInt(2)
                        || channelId != rows.getInt(3) || !java.util.Arrays.equals(snapshot, rows.getBytes(4))
                        || value != rows.getLong(5) || !reason.equals(rows.getString(6)))
                    throw new SQLException("Conflicting venue acquisition operation");
                return true;
            }
        }
    }

    public static boolean openRound(UUID roundId, int mapId, int channelId, String hostBotName,
                                    Integer humanId, int tierMesos) throws SQLException {
        return openRound(roundId, mapId, channelId, hostBotName, humanId, tierMesos, false);
    }

    static boolean openSocialRound(UUID roundId, int mapId, int channelId, String hostBotName,
                                   int humanId) throws SQLException {
        return openRound(roundId, mapId, channelId, hostBotName, humanId, 0, true);
    }

    private static boolean openRound(UUID roundId, int mapId, int channelId, String hostBotName,
                                     Integer humanId, int tierMesos, boolean social) throws SQLException {
        if (roundId == null || (!social && !isVenue(mapId)) || channelId <= 0 || hostBotName == null
                || hostBotName.isBlank() || hostBotName.length() > 32
                || humanId != null && humanId <= 0
                || tierMesos != 0 && tierMesos != 10_000_000 && tierMesos != 50_000_000)
            throw new IllegalArgumentException("venue round");
        if (social) {
            if (humanId == null || tierMesos != 0 || !TrainerSocialStock.eligible(hostBotName, mapId, channelId))
                throw new IllegalArgumentException("social admission");
        } else if (humanId == null && tierMesos != 0 || humanId != null && tierMesos == 0)
            throw new IllegalArgumentException("venue admission");
        long now = System.currentTimeMillis();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT IGNORE INTO trainer_venue_rounds (round_id, venue_map_id, channel_id, "
                             + "host_bot_name, player_character_id, tier_mesos, phase, opened_at_ms, updated_at_ms) "
                             + "VALUES (?, ?, ?, ?, ?, ?, 'STARTING', ?, ?)")) {
            if (social) {
                try (PreparedStatement cooldown = connection.prepareStatement(
                        "SELECT 1 FROM trainer_venue_rounds WHERE host_bot_name = ? AND player_character_id = ? "
                                + "AND tier_mesos = 0 AND opened_at_ms > ? AND round_id <> ? LIMIT 1")) {
                    cooldown.setString(1, hostBotName); cooldown.setInt(2, humanId);
                    cooldown.setLong(3, now - 60_000); cooldown.setString(4, roundId.toString());
                    try (ResultSet rows = cooldown.executeQuery()) { if (rows.next()) return false; }
                }
            }
            insert.setString(1, roundId.toString());
            insert.setInt(2, mapId);
            insert.setInt(3, channelId);
            insert.setString(4, hostBotName);
            if (humanId == null) insert.setNull(5, java.sql.Types.INTEGER);
            else insert.setInt(5, humanId);
            insert.setInt(6, tierMesos);
            insert.setLong(7, now);
            insert.setLong(8, now);
            if (insert.executeUpdate() == 1) return true;
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT venue_map_id, channel_id, host_bot_name, player_character_id, tier_mesos "
                            + "FROM trainer_venue_rounds WHERE round_id = ?")) {
                existing.setString(1, roundId.toString());
                try (ResultSet rows = existing.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Venue round insert failed");
                    int recordedPlayer = rows.getInt(4);
                    Integer recordedId = rows.wasNull() ? null : recordedPlayer;
                    if (rows.getInt(1) != mapId || rows.getInt(2) != channelId
                            || !hostBotName.equals(rows.getString(3))
                            || !java.util.Objects.equals(humanId, recordedId) || tierMesos != rows.getInt(5))
                        throw new SQLException("Conflicting venue round identity");
                }
            }
            return false;
        }
    }

    public static UUID stepId(UUID roundId, String step) {
        return UUID.nameUUIDFromBytes((roundId + ":" + step).getBytes(StandardCharsets.US_ASCII));
    }

    /** A paid admission first records its intended debit, then the player and house receipts. */
    public static boolean admit(UUID roundId, Character player) throws SQLException {
        if (roundId == null || player == null) throw new IllegalArgumentException("venue admission");
        int mapId, channelId, tier;
        UUID debit = stepId(roundId, "admission-debit");
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement round = connection.prepareStatement(
                    "SELECT venue_map_id, channel_id, player_character_id, tier_mesos, phase, "
                            + "admission_operation_id FROM trainer_venue_rounds WHERE round_id = ? FOR UPDATE")) {
                round.setString(1, roundId.toString());
                try (ResultSet rows = round.executeQuery()) {
                    if (!rows.next() || rows.getInt(3) != player.getId() || rows.getInt(4) <= 0
                            || !"STARTING".equals(rows.getString(5))
                            && !"WAITING".equals(rows.getString(5))) {
                        connection.rollback(); return false;
                    }
                    String recorded = rows.getString(6);
                    if (recorded != null && !debit.toString().equals(recorded))
                        throw new SQLException("Conflicting venue admission debit");
                    mapId = rows.getInt(1);
                    channelId = rows.getInt(2);
                    tier = rows.getInt(4);
                }
                try (PreparedStatement intent = connection.prepareStatement(
                        "UPDATE trainer_venue_rounds SET admission_operation_id = ?, updated_at_ms = ? "
                                + "WHERE round_id = ?")) {
                    intent.setString(1, debit.toString());
                    intent.setLong(2, System.currentTimeMillis());
                    intent.setString(3, roundId.toString());
                    intent.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) { connection.rollback(); throw failure; }
        }
        Character.DurableMesoResult paid = player.adjustVenueMeso(debit.toString(), roundId.toString(), -tier);
        if (paid == Character.DurableMesoResult.REJECTED) return false;
        treasury(roundId, stepId(roundId, "admission-house"), mapId, channelId, tier, "ADMISSION");
        setPhase(roundId, "WAITING");
        return true;
    }

    /** Refund the same committed entry at most once; a failed credit stays pending. */
    public static boolean refundBeforePlay(UUID roundId, Character player) throws SQLException {
        if (roundId == null || player == null) throw new IllegalArgumentException("venue refund");
        int mapId, channelId, tier;
        UUID admission = stepId(roundId, "admission-debit");
        UUID refund = stepId(roundId, "entry-refund");
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement round = connection.prepareStatement(
                     "SELECT venue_map_id, channel_id, player_character_id, tier_mesos, phase "
                             + "FROM trainer_venue_rounds WHERE round_id = ?")) {
            round.setString(1, roundId.toString());
            try (ResultSet rows = round.executeQuery()) {
                if (!rows.next() || rows.getInt(3) != player.getId()
                        || !"STARTING".equals(rows.getString(5))
                        && !"WAITING".equals(rows.getString(5))
                        && !"REFUND_PENDING".equals(rows.getString(5))) return false;
                mapId = rows.getInt(1); channelId = rows.getInt(2); tier = rows.getInt(4);
            }
        }
        if (!mesoReceiptMatches(admission, roundId, player.getId(), -tier)) {
            setPhase(roundId, "CLOSED"); return true;
        }
        setPhase(roundId, "REFUND_PENDING");
        // A crash after the player debit but before the house receipt is repaired
        // with the same deterministic operation ID before the refund draws funds.
        treasury(roundId, stepId(roundId, "admission-house"), mapId, channelId,
                tier, "ADMISSION");
        treasury(roundId, stepId(roundId, "entry-refund-house"), mapId, channelId,
                -tier, "ENTRY_REFUND");
        Character.DurableMesoResult restored = player.adjustVenueMeso(
                refund.toString(), roundId.toString(), tier);
        if (restored == Character.DurableMesoResult.REJECTED) return false;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement mark = connection.prepareStatement(
                     "UPDATE trainer_venue_rounds SET refund_operation_id = ? WHERE round_id = ?")) {
            mark.setString(1, refund.toString());
            mark.setString(2, roundId.toString());
            mark.executeUpdate();
        }
        setPhase(roundId, "CLOSED");
        return true;
    }

    /** Disconnected players are refunded using a fresh Character at their next login. */
    public static void deferRefund(UUID roundId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE trainer_venue_rounds SET phase = 'REFUND_PENDING', updated_at_ms = ? "
                             + "WHERE round_id = ? AND phase IN ('STARTING', 'WAITING', 'ESCROW', 'REFUND_PENDING')")) {
            update.setLong(1, System.currentTimeMillis()); update.setString(2, roundId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Round cannot defer entry refund");
        }
    }

    public record PendingRefund(UUID roundId, int playerId) { }
    public static List<PendingRefund> pendingRefunds() throws SQLException {
        List<PendingRefund> result = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT round_id, player_character_id FROM trainer_venue_rounds "
                             + "WHERE phase = 'REFUND_PENDING' AND player_character_id IS NOT NULL LIMIT 100")) {
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new PendingRefund(UUID.fromString(rows.getString(1)), rows.getInt(2)));
            }
        }
        return result;
    }

    private static boolean mesoReceiptMatches(UUID operationId, UUID roundId, int playerId,
                                              int delta) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT round_id, actor_character_id, delta FROM trainer_venue_meso_ops "
                             + "WHERE operation_id = ?")) {
            query.setString(1, operationId.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return false;
                if (!roundId.toString().equals(rows.getString(1)) || rows.getInt(2) != playerId
                        || rows.getInt(3) != delta)
                    throw new SQLException("Conflicting venue meso receipt");
                return true;
            }
        }
    }

    /** One audited house transfer, with a nonnegative treasury balance. */
    public static boolean treasury(UUID roundId, UUID operationId, int mapId, int channelId,
                                   long delta, String reason) throws SQLException {
        if (roundId == null || operationId == null || !isVenue(mapId) || channelId <= 0
                || delta == 0 || delta == Long.MIN_VALUE || reason == null || reason.isBlank() || reason.length() > 24)
            throw new IllegalArgumentException("venue treasury operation");
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (treasuryOperationExists(connection, roundId, operationId,
                        mapId, channelId, delta, reason)) {
                    connection.rollback(); return false;
                }
                try (PreparedStatement create = connection.prepareStatement(
                        "INSERT IGNORE INTO trainer_venue_treasury "
                                + "(venue_map_id, channel_id, balance) VALUES (?, ?, 0)")) {
                    create.setInt(1, mapId); create.setInt(2, channelId); create.executeUpdate();
                }
                long balance;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT balance FROM trainer_venue_treasury "
                                + "WHERE venue_map_id = ? AND channel_id = ? FOR UPDATE")) {
                    lock.setInt(1, mapId); lock.setInt(2, channelId);
                    try (ResultSet rows = lock.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Venue treasury missing");
                        balance = rows.getLong(1);
                    }
                }
                if (delta < 0 && balance < -delta || delta > 0 && balance > Long.MAX_VALUE - delta)
                    throw new SQLException("Venue treasury lacks funds or overflows");
                long pot;
                try (PreparedStatement lockRound = connection.prepareStatement(
                        "SELECT venue_map_id, channel_id, pot_balance, phase FROM trainer_venue_rounds "
                                + "WHERE round_id = ? FOR UPDATE")) {
                    lockRound.setString(1, roundId.toString());
                    try (ResultSet rows = lockRound.executeQuery()) {
                        if (!rows.next() || rows.getInt(1) != mapId || rows.getInt(2) != channelId)
                            throw new SQLException("Venue treasury round mismatch");
                        if (List.of("CLOSED", "FAULT").contains(rows.getString(4)))
                            throw new SQLException("Venue treasury round is terminal");
                        pot = rows.getLong(3);
                    }
                }
                if (delta < 0 && pot < -delta || delta > 0 && pot > Long.MAX_VALUE - delta)
                    throw new SQLException("Venue round pot lacks funds or overflows");
                try (PreparedStatement operation = connection.prepareStatement(
                        "INSERT INTO trainer_venue_treasury_ops (operation_id, round_id, "
                                + "venue_map_id, channel_id, delta, reason, created_at_ms) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    operation.setString(1, operationId.toString());
                    operation.setString(2, roundId.toString());
                    operation.setInt(3, mapId); operation.setInt(4, channelId);
                    operation.setLong(5, delta); operation.setString(6, reason);
                    operation.setLong(7, System.currentTimeMillis());
                    operation.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE trainer_venue_treasury SET balance = ? "
                                + "WHERE venue_map_id = ? AND channel_id = ?")) {
                    update.setLong(1, balance + delta);
                    update.setInt(2, mapId); update.setInt(3, channelId);
                    if (update.executeUpdate() != 1) throw new SQLException("Venue treasury update failed");
                }
                try (PreparedStatement updatePot = connection.prepareStatement(
                        "UPDATE trainer_venue_rounds SET pot_balance = ?, updated_at_ms = ? "
                                + "WHERE round_id = ?")) {
                    updatePot.setLong(1, pot + delta);
                    updatePot.setLong(2, System.currentTimeMillis());
                    updatePot.setString(3, roundId.toString());
                    if (updatePot.executeUpdate() != 1) throw new SQLException("Venue round pot update failed");
                }
                connection.commit(); return true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                if (failure instanceof SQLException && treasuryOperationExists(connection,
                        roundId, operationId, mapId, channelId, delta, reason)) return false;
                throw failure;
            }
        }
    }

    private static boolean treasuryOperationExists(Connection connection, UUID roundId,
                                                   UUID operationId, int mapId, int channelId,
                                                   long delta, String reason)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT round_id, venue_map_id, channel_id, delta, reason "
                        + "FROM trainer_venue_treasury_ops WHERE operation_id = ?")) {
            query.setString(1, operationId.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return false;
                if (!roundId.toString().equals(rows.getString(1)) || rows.getInt(2) != mapId
                        || rows.getInt(3) != channelId || rows.getLong(4) != delta
                        || !reason.equals(rows.getString(5)))
                    throw new SQLException("Conflicting venue treasury operation");
                return true;
            }
        }
    }

    private static void setPhase(UUID roundId, String phase) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            setPhase(connection, roundId, phase);
        }
    }

    /** Reserve one existing asset. No fallback generator is permitted. */
    public static Optional<Asset> reserve(UUID roundId, UUID operationId, long minValue,
                                          long maxValue) throws SQLException {
        return reserve(roundId, operationId, minValue, maxValue, null);
    }

    static Optional<Asset> reservePersonal(UUID roundId, UUID operationId, long minValue,
                                           long maxValue, String owner) throws SQLException {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("social owner");
        return reserve(roundId, operationId, minValue, maxValue, owner);
    }

    private static Optional<Asset> reserve(UUID roundId, UUID operationId, long minValue,
                                           long maxValue, String personalOwner) throws SQLException {
        if (roundId == null || operationId == null || minValue < 0 || maxValue < minValue)
            throw new IllegalArgumentException("venue reserve");
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Asset> existing = reservedByOperation(connection, roundId, operationId);
                if (existing.isPresent()) {
                    if (personalOwner != null && !personalOwner.equals(existing.get().ownerBotName()))
                        throw new SQLException("Personal reservation replay owner mismatch");
                    connection.rollback(); return existing;
                }
                int mapId, channelId;
                try (PreparedStatement round = connection.prepareStatement(
                        "SELECT venue_map_id, channel_id, phase, host_bot_name, tier_mesos FROM trainer_venue_rounds "
                                + "WHERE round_id = ? FOR UPDATE")) {
                    round.setString(1, roundId.toString());
                    try (ResultSet rows = round.executeQuery()) {
                        if (!rows.next() || !List.of("STARTING", "WAITING", "ESCROW", "ROUND")
                                .contains(rows.getString(3))) {
                            connection.rollback(); return Optional.empty();
                        }
                        mapId = rows.getInt(1);
                        channelId = rows.getInt(2);
                        if (personalOwner == null && TrainerSocialStock.eligible(rows.getString(4), mapId, channelId))
                            throw new SQLException("Personal round requires owner-specific stock");
                        if (personalOwner != null && (!personalOwner.equals(rows.getString(4))
                                || rows.getInt(5) != 0 || !TrainerSocialStock.eligible(personalOwner, mapId, channelId)))
                            throw new SQLException("Personal reservation owner mismatch");
                    }
                }
                Asset asset;
                try (PreparedStatement stock = connection.prepareStatement(
                        "SELECT asset_id, owner_bot_name, item_snapshot, estimated_value "
                                + "FROM trainer_venue_stock WHERE venue_map_id = ? AND channel_id = ? "
                                + "AND state = 'AVAILABLE' AND estimated_value BETWEEN ? AND ? "
                                + (personalOwner == null ? "AND acquisition_reason <> 'SOCIAL_CAMPAIGN_V1' "
                                    : "AND acquisition_reason = 'SOCIAL_CAMPAIGN_V1' AND owner_bot_name = ? ")
                                + "ORDER BY estimated_value, asset_id LIMIT 1 FOR UPDATE")) {
                    stock.setInt(1, mapId); stock.setInt(2, channelId);
                    stock.setLong(3, minValue); stock.setLong(4, maxValue);
                    if (personalOwner != null) stock.setString(5, personalOwner);
                    try (ResultSet rows = stock.executeQuery()) {
                        if (!rows.next()) { connection.rollback(); return Optional.empty(); }
                        asset = new Asset(UUID.fromString(rows.getString(1)), mapId, channelId,
                                rows.getString(2), TrainerVenueItemCodec.decode(rows.getBytes(3)),
                                rows.getLong(4));
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE trainer_venue_stock SET state = 'ESCROW', round_id = ?, changed_at_ms = ? "
                                + "WHERE asset_id = ? AND state = 'AVAILABLE'")) {
                    update.setString(1, roundId.toString());
                    update.setLong(2, System.currentTimeMillis());
                    update.setString(3, asset.id().toString());
                    if (update.executeUpdate() != 1) throw new SQLException("Venue stock reservation race");
                }
                recordOperation(connection, operationId, roundId, asset.id(), "RESERVE", null);
                connection.commit();
                return Optional.of(asset);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                if (failure instanceof SQLException) {
                    Optional<Asset> existing = reservedByOperation(connection, roundId, operationId);
                    if (existing.isPresent()) return existing;
                }
                throw failure;
            }
        }
    }

    private static Optional<Asset> reservedByOperation(Connection connection, UUID roundId,
                                                       UUID operationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT s.asset_id, s.venue_map_id, s.channel_id, s.owner_bot_name, "
                        + "s.item_snapshot, s.estimated_value, o.round_id, o.operation_kind, s.state, s.round_id "
                        + "FROM trainer_venue_asset_ops o JOIN trainer_venue_stock s ON s.asset_id = o.asset_id "
                        + "WHERE o.operation_id = ?")) {
            query.setString(1, operationId.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                if (!roundId.toString().equals(rows.getString(7)) || !"RESERVE".equals(rows.getString(8)))
                    throw new SQLException("Conflicting venue reservation operation");
                if (!"ESCROW".equals(rows.getString(9)) || !roundId.toString().equals(rows.getString(10)))
                    throw new SQLException("Venue reservation operation already settled or exposed");
                return Optional.of(new Asset(UUID.fromString(rows.getString(1)), rows.getInt(2),
                        rows.getInt(3), rows.getString(4),
                        TrainerVenueItemCodec.decode(rows.getBytes(5)), rows.getLong(6)));
            }
        }
    }

    /** Persist exposure intent before creating a visible world drop. */
    public static boolean expose(UUID roundId, UUID assetId, UUID operationId) throws SQLException {
        return transition(roundId, assetId, operationId, "ESCROW", "EXPOSED", "EXPOSE", "ROUND", null);
    }

    /** Caller must have removed the drop under its item lock before this return. */
    public static boolean returnUnclaimed(UUID roundId, UUID assetId, UUID operationId)
            throws SQLException {
        return transition(roundId, assetId, operationId, "EXPOSED", "AVAILABLE", "RETURN", "SETTLE", null);
    }

    public static boolean returnEscrow(UUID roundId, UUID assetId) throws SQLException {
        return transition(roundId, assetId, stepId(roundId, assetId + ":escrow-return"),
                "ESCROW", "AVAILABLE", "ESCROW_RETURN", "WAITING", null);
    }

    public static int availableCount(int mapId, int channelId) throws SQLException {
        if (!isVenue(mapId) || channelId <= 0) throw new IllegalArgumentException("venue route");
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM trainer_venue_stock WHERE venue_map_id = ? "
                             + "AND channel_id = ? AND state = 'AVAILABLE' AND acquisition_reason <> 'SOCIAL_CAMPAIGN_V1'")) {
            query.setInt(1, mapId); query.setInt(2, channelId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static boolean transition(UUID roundId, UUID assetId, UUID operationId, String from,
                                      String to, String kind, String phase, Integer actorId)
            throws SQLException {
        if (roundId == null || assetId == null || operationId == null)
            throw new IllegalArgumentException("venue transition");
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockOpenRound(connection, roundId);
                try (PreparedStatement stock = connection.prepareStatement(
                        "UPDATE trainer_venue_stock SET state = ?, round_id = ?, "
                                + "claimed_by_character_id = ?, changed_at_ms = ? "
                                + "WHERE asset_id = ? AND round_id = ? AND state = ?")) {
                    stock.setString(1, to);
                    if ("AVAILABLE".equals(to)) stock.setNull(2, java.sql.Types.CHAR);
                    else stock.setString(2, roundId.toString());
                    if (actorId == null) stock.setNull(3, java.sql.Types.INTEGER);
                    else stock.setInt(3, actorId);
                    stock.setLong(4, System.currentTimeMillis());
                    stock.setString(5, assetId.toString());
                    stock.setString(6, roundId.toString());
                    stock.setString(7, from);
                    if (stock.executeUpdate() != 1) { connection.rollback(); return false; }
                }
                recordOperation(connection, operationId, roundId, assetId, kind, actorId);
                if ("EXPOSE".equals(kind)) setPhase(connection, roundId, "ROUND");
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void recordOperation(Connection connection, UUID operationId, UUID roundId,
                                        UUID assetId, String kind, Integer actorId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trainer_venue_asset_ops (operation_id, round_id, asset_id, "
                        + "operation_kind, actor_character_id, created_at_ms) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, operationId.toString());
            insert.setString(2, roundId.toString());
            insert.setString(3, assetId.toString());
            insert.setString(4, kind);
            if (actorId == null) insert.setNull(5, java.sql.Types.INTEGER);
            else insert.setInt(5, actorId);
            insert.setLong(6, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    private static void setPhase(Connection connection, UUID roundId, String phase) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE trainer_venue_rounds SET phase = ?, updated_at_ms = ? WHERE round_id = ? "
                        + "AND phase NOT IN ('CLOSED', 'FAULT')")) {
            update.setString(1, phase);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, roundId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Venue round missing");
        }
    }

    private static void lockOpenRound(Connection connection, UUID roundId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT phase FROM trainer_venue_rounds WHERE round_id = ? FOR UPDATE")) {
            query.setString(1, roundId.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || List.of("CLOSED", "FAULT").contains(rows.getString(1)))
                    throw new SQLException("Venue round is not open");
            }
        }
    }

    /** Lock and validate the exact finite item before saving it into a human inventory. */
    public static Asset lockExposedAsset(Connection connection, UUID roundId, UUID assetId,
                                         int mapId, int channelId) throws SQLException {
        lockOpenRound(connection, roundId);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT owner_bot_name, item_snapshot, estimated_value FROM trainer_venue_stock "
                        + "WHERE asset_id = ? AND round_id = ? AND venue_map_id = ? "
                        + "AND channel_id = ? AND state = 'EXPOSED' FOR UPDATE")) {
            query.setString(1, assetId.toString()); query.setString(2, roundId.toString());
            query.setInt(3, mapId); query.setInt(4, channelId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                return new Asset(assetId, mapId, channelId, rows.getString(1),
                        TrainerVenueItemCodec.decode(rows.getBytes(2)), rows.getLong(3));
            }
        }
    }

    /** Caller commits this connection together with the recipient's inventory snapshot. */
    public static void claimToHumanInventory(Connection connection, UUID roundId, UUID assetId,
                                             int actorId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE trainer_venue_stock SET state = 'CLAIMED', claimed_by_character_id = ?, "
                        + "changed_at_ms = ? WHERE asset_id = ? AND round_id = ? AND state = 'EXPOSED'")) {
            update.setInt(1, actorId); update.setLong(2, System.currentTimeMillis());
            update.setString(3, assetId.toString()); update.setString(4, roundId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Venue asset already settled");
        }
        recordOperation(connection, stepId(assetId, "human-claim"), roundId, assetId,
                "HUMAN_CLAIM", actorId);
    }

    /** Actual bot-to-bot stock transfer; its world drop is removed under the same runtime item lock. */
    public static boolean claimToBot(UUID roundId, UUID assetId, int mapId, int channelId,
                                     int actorId, String actorName, UUID operationId) throws SQLException {
        if (actorId <= 0 || actorName == null || !actorName.matches("[A-Za-z0-9]{4,13}") || operationId == null)
            throw new IllegalArgumentException("venue bot claim");
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Asset asset = lockExposedAsset(connection, roundId, assetId, mapId, channelId);
                if (asset == null) { connection.rollback(); return false; }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE trainer_venue_stock SET state = 'AVAILABLE', owner_bot_name = ?, "
                                + "round_id = NULL, claimed_by_character_id = NULL, changed_at_ms = ? "
                                + "WHERE asset_id = ? AND round_id = ? AND state = 'EXPOSED'")) {
                    update.setString(1, actorName); update.setLong(2, System.currentTimeMillis());
                    update.setString(3, assetId.toString()); update.setString(4, roundId.toString());
                    if (update.executeUpdate() != 1) throw new SQLException("Venue bot asset race");
                }
                recordOperation(connection, operationId, roundId,
                        assetId, "BOT_CLAIM", actorId);
                connection.commit(); return true;
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    /** A terminal round cannot hide an exposed or transfer-pending asset. */
    public static boolean closeRound(UUID roundId) throws SQLException {
        if (roundId == null) throw new IllegalArgumentException("venue round");
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT phase FROM trainer_venue_rounds WHERE round_id = ? FOR UPDATE")) {
                    lock.setString(1, roundId.toString());
                    try (ResultSet rows = lock.executeQuery()) {
                        if (!rows.next() || "FAULT".equals(rows.getString(1))) {
                            connection.rollback(); return false;
                        }
                        if ("CLOSED".equals(rows.getString(1))) {
                            connection.rollback(); return true;
                        }
                    }
                }
                try (PreparedStatement active = connection.prepareStatement(
                        "SELECT 1 FROM trainer_venue_stock WHERE round_id = ? "
                                + "AND state IN ('ESCROW', 'EXPOSED', 'CLAIM_PENDING') LIMIT 1")) {
                    active.setString(1, roundId.toString());
                    try (ResultSet rows = active.executeQuery()) {
                        if (rows.next()) { connection.rollback(); return false; }
                    }
                }
                setPhase(connection, roundId, "CLOSED");
                connection.commit(); return true;
            } catch (SQLException failure) { connection.rollback(); throw failure; }
        }
    }

    private record InterruptedRound(UUID id, Integer playerId, int tier, String phase) { }

    /** Before login: return unsettled assets, retain committed claims, refund only pre-play. */
    public static void recoverBeforeLogin() throws SQLException {
        List<InterruptedRound> interrupted = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT round_id, player_character_id, tier_mesos, phase "
                             + "FROM trainer_venue_rounds WHERE phase NOT IN ('CLOSED', 'FAULT')")) {
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    int player = rows.getInt(2);
                    boolean noPlayer = rows.wasNull();
                    interrupted.add(new InterruptedRound(UUID.fromString(rows.getString(1)),
                            noPlayer ? null : player, rows.getInt(3), rows.getString(4)));
                }
            }
        }
        for (InterruptedRound round : interrupted) {
            try {
                if (unsafeForAutomaticRecovery(round.id())) continue;
                returnUnclaimedAtStartup(round.id());
                if (round.playerId() != null && round.tier() > 0
                        && ("STARTING".equals(round.phase()) || "WAITING".equals(round.phase())
                        || "ESCROW".equals(round.phase()) || "REFUND_PENDING".equals(round.phase()))) {
                    refundOfflineAtStartup(round.id(), round.playerId(), round.tier());
                } else closeRound(round.id());
            } catch (SQLException | RuntimeException failure) {
                // The final fault pass leaves this round visible for an operator.
                org.slf4j.LoggerFactory.getLogger(TrainerVenueLedger.class)
                        .error("Venue startup recovery needs audit round={}", round.id(), failure);
            }
        }
        faultUnsettledAtStartup();
    }

    private static boolean unsafeForAutomaticRecovery(UUID roundId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT 1 FROM trainer_venue_stock WHERE round_id = ? "
                             + "AND state = 'CLAIM_PENDING' LIMIT 1")) {
            query.setString(1, roundId.toString());
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private static void returnUnclaimedAtStartup(UUID roundId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockOpenRound(connection, roundId);
                List<UUID> assets = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT asset_id FROM trainer_venue_stock WHERE round_id = ? "
                                + "AND state IN ('ESCROW', 'EXPOSED') FOR UPDATE")) {
                    query.setString(1, roundId.toString());
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) assets.add(UUID.fromString(rows.getString(1)));
                    }
                }
                for (UUID asset : assets) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE trainer_venue_stock SET state = 'AVAILABLE', round_id = NULL, "
                                    + "changed_at_ms = ? WHERE asset_id = ? AND round_id = ? "
                                    + "AND state IN ('ESCROW', 'EXPOSED')")) {
                        update.setLong(1, System.currentTimeMillis());
                        update.setString(2, asset.toString()); update.setString(3, roundId.toString());
                        if (update.executeUpdate() != 1) throw new SQLException("Venue recovery asset race");
                    }
                    recordOperation(connection, stepId(roundId, asset + ":recovery-return"),
                            roundId, asset, "RECOVERY_RETURN", null);
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }

    /** Runtime counterpart of recovery, after removing this social round's visible drop. */
    static void returnPersonalUnclaimed(UUID roundId) throws SQLException {
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT venue_map_id, channel_id, host_bot_name, tier_mesos, phase FROM trainer_venue_rounds WHERE round_id = ?")) {
            q.setString(1, roundId.toString());
            try (ResultSet rows = q.executeQuery()) {
                if (!rows.next() || rows.getInt(4) != 0
                        || !TrainerSocialStock.eligible(rows.getString(3), rows.getInt(1), rows.getInt(2)))
                    throw new SQLException("Not a personal stake round");
                if ("CLOSED".equals(rows.getString(5))) return;
            }
        }
        returnUnclaimedAtStartup(roundId);
    }

    public static boolean hasFault(int mapId, int channelId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT 1 FROM trainer_venue_rounds WHERE venue_map_id = ? AND channel_id = ? "
                             + "AND phase = 'FAULT' LIMIT 1")) {
            query.setInt(1, mapId); query.setInt(2, channelId);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    public static void faultVenue(int mapId, int channelId, String reason) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE trainer_venue_rounds SET phase = 'FAULT', fault_reason = ?, updated_at_ms = ? "
                             + "WHERE venue_map_id = ? AND channel_id = ? AND phase NOT IN ('CLOSED', 'FAULT')")) {
            update.setString(1, reason.substring(0, Math.min(reason.length(), 255)));
            update.setLong(2, System.currentTimeMillis()); update.setInt(3, mapId); update.setInt(4, channelId);
            update.executeUpdate();
        }
    }

    /** Fresh-connection receipt resolves an acknowledgement lost after COMMIT. */
    public static boolean humanClaimCommitted(UUID roundId, UUID assetId, int actorId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT round_id, actor_character_id, operation_kind FROM trainer_venue_asset_ops "
                             + "WHERE operation_id = ?")) {
            query.setString(1, stepId(assetId, "human-claim").toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return false;
                if (!roundId.toString().equals(rows.getString(1)) || actorId != rows.getInt(2)
                        || !"HUMAN_CLAIM".equals(rows.getString(3)))
                    throw new SQLException("Conflicting venue human claim receipt");
                return true;
            }
        }
    }

    private static void refundOfflineAtStartup(UUID roundId, int playerId, int tier)
            throws SQLException {
        UUID debit = stepId(roundId, "admission-debit");
        if (!mesoReceiptMatches(debit, roundId, playerId, -tier)) {
            setPhase(roundId, "CLOSED"); return;
        }
        int mapId, channelId;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement round = connection.prepareStatement(
                     "SELECT venue_map_id, channel_id FROM trainer_venue_rounds WHERE round_id = ?")) {
            round.setString(1, roundId.toString());
            try (ResultSet rows = round.executeQuery()) {
                if (!rows.next()) throw new SQLException("Venue round missing during recovery");
                mapId = rows.getInt(1); channelId = rows.getInt(2);
            }
        }
        setPhase(roundId, "REFUND_PENDING");
        treasury(roundId, stepId(roundId, "admission-house"), mapId, channelId,
                tier, "ADMISSION");
        treasury(roundId, stepId(roundId, "entry-refund-house"), mapId, channelId,
                -tier, "ENTRY_REFUND");
        UUID credit = stepId(roundId, "entry-refund");
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement previous = connection.prepareStatement(
                        "SELECT round_id, actor_character_id, delta FROM trainer_venue_meso_ops "
                                + "WHERE operation_id = ?")) {
                    previous.setString(1, credit.toString());
                    try (ResultSet rows = previous.executeQuery()) {
                        if (rows.next()) {
                            if (!roundId.toString().equals(rows.getString(1)) || rows.getInt(2) != playerId
                                    || rows.getInt(3) != tier)
                                throw new SQLException("Conflicting venue offline refund");
                            connection.rollback(); setPhase(roundId, "CLOSED"); return;
                        }
                    }
                }
                int balance;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT meso FROM characters WHERE id = ? FOR UPDATE")) {
                    lock.setInt(1, playerId);
                    try (ResultSet rows = lock.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Venue refund character missing");
                        balance = rows.getInt(1);
                    }
                }
                long next = (long) balance + tier;
                if (next > Integer.MAX_VALUE) throw new SQLException("Venue refund balance overflow");
                try (PreparedStatement receipt = connection.prepareStatement(
                        "INSERT INTO trainer_venue_meso_ops "
                                + "(operation_id, round_id, actor_character_id, delta, balance_after, created_at_ms) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    receipt.setString(1, credit.toString()); receipt.setString(2, roundId.toString());
                    receipt.setInt(3, playerId); receipt.setInt(4, tier);
                    receipt.setInt(5, (int) next); receipt.setLong(6, System.currentTimeMillis());
                    receipt.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE characters SET meso = ? WHERE id = ?")) {
                    update.setInt(1, (int) next); update.setInt(2, playerId);
                    if (update.executeUpdate() != 1) throw new SQLException("Venue refund balance update failed");
                }
                connection.commit();
            } catch (SQLException failure) { connection.rollback(); throw failure; }
        }
        setPhase(roundId, "CLOSED");
    }

    /** Conservative boot barrier: unresolved world-drop phases require an operator audit. */
    public static int faultUnsettledAtStartup() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE trainer_venue_rounds SET phase = 'FAULT', "
                            + "fault_reason = 'startup reconciliation required', updated_at_ms = ? "
                            + "WHERE phase NOT IN ('CLOSED', 'FAULT')")) {
                update.setLong(1, System.currentTimeMillis());
                int faulted = update.executeUpdate();
                connection.commit();
                return faulted;
            } catch (SQLException failure) {
                connection.rollback(); throw failure;
            }
        }
    }
}

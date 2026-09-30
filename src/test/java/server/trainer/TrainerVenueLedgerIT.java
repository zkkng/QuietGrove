package server.trainer;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import tools.DatabaseConnection;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in MariaDB tests. Refuses every database except the disposable closeout schema. */
class TrainerVenueLedgerIT {
    private static HikariDataSource pool;
    private static Object priorPool;
    private static final int MAP = 105040401, CHANNEL = 1, PLAYER = 900001;
    private static final String[] TABLES = {"trainer_social_memory", "trainer_social_incidents", "trainer_venue_asset_ops", "trainer_venue_stock",
            "trainer_venue_treasury_ops", "trainer_venue_treasury", "trainer_venue_meso_ops",
            "trainer_venue_rounds", "trainer_venue_daily_budget", "inventoryequipment", "inventoryitems", "characters"};

    @BeforeAll static void connect() throws Exception {
        String url = System.getenv("TRAINER_TEST_JDBC_URL");
        if (url == null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/trainer_closeout_test(?:\\?.*)?"))
            throw new IllegalStateException("Requires local tunnel to disposable trainer_closeout_test");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url); config.setUsername(System.getenv("TRAINER_TEST_DB_USER"));
        config.setPassword(System.getenv("TRAINER_TEST_DB_PASSWORD"));
        config.setMaximumPoolSize(16); config.setConnectionTimeout(10_000);
        pool = new HikariDataSource(config);
        try (Connection connection = pool.getConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT DATABASE()")) {
            assertTrue(rows.next()); assertEquals("trainer_closeout_test", rows.getString(1));
        }
        Field field = DatabaseConnection.class.getDeclaredField("dataSource");
        field.setAccessible(true); priorPool = field.get(null); field.set(null, pool);
    }

    @AfterAll static void disconnect() throws Exception {
        if (pool == null) return;
        Field field = DatabaseConnection.class.getDeclaredField("dataSource");
        field.setAccessible(true); field.set(null, priorPool); pool.close();
    }

    @BeforeEach void clearDisposableTables() throws Exception {
        try (Connection connection = pool.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT DATABASE()")) {
                assertTrue(rows.next()); assertEquals("trainer_closeout_test", rows.getString(1));
            }
            for (String table : TABLES) statement.executeUpdate("DELETE FROM " + table);
            statement.executeUpdate("INSERT INTO characters (id,name,meso) VALUES (900001,'VenueTester',100000000)");
        }
    }

    private long scalar(String sql) throws Exception {
        try (Connection connection = pool.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private void execute(String sql) throws Exception {
        try (Connection connection = pool.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate(sql); }
    }
    private UUID acquire(long value) throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(TrainerVenueLedger.acquire(id, TrainerVenueLedger.stepId(id, "issue"), MAP, CHANNEL,
                "VenueHost", new Item(2040811, (short) 0, (short) 1), value, "TEST_BUDGET"));
        return id;
    }
    private UUID round(Integer player, int tier) throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(TrainerVenueLedger.openRound(id, MAP, CHANNEL, "VenueHost", player, tier)); return id;
    }
    private UUID reserve(UUID round) throws Exception {
        return TrainerVenueLedger.reserve(round, TrainerVenueLedger.stepId(round, "reserve"), 1, 100_000_000).orElseThrow().id();
    }
    private void expose(UUID round, UUID asset) throws Exception {
        assertTrue(TrainerVenueLedger.expose(round, asset, TrainerVenueLedger.stepId(round, "expose")));
    }
    private Character player() throws Exception {
        Character actor = mock(Character.class);
        when(actor.getId()).thenReturn(PLAYER);
        for (var entry : java.util.Map.of("meso", new AtomicInteger(100_000_000), "petLock", new ReentrantLock()).entrySet()) {
            Field field = Character.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(actor, entry.getValue());
        }
        doCallRealMethod().when(actor).adjustVenueMeso(anyString(), anyString(), anyInt());
        return actor;
    }

    @Test void duplicateAcquisitionAndBudgetCannotMintExtraStock() throws Exception {
        UUID asset = acquire(50_000_000);
        assertFalse(TrainerVenueLedger.acquire(asset, TrainerVenueLedger.stepId(asset, "issue"), MAP, CHANNEL,
                "VenueHost", new Item(2040811, (short) 0, (short) 1), 50_000_000, "TEST_BUDGET"));
        assertThrows(SQLException.class, () -> TrainerVenueLedger.acquire(asset, TrainerVenueLedger.stepId(asset, "issue"), MAP, CHANNEL,
                "VenueHost", new Item(2040811, (short) 0, (short) 1), 1, "TEST_BUDGET"));
        acquire(50_000_000);
        UUID over = UUID.randomUUID();
        assertFalse(TrainerVenueLedger.acquire(over, UUID.randomUUID(), MAP, CHANNEL, "VenueHost",
                new Item(2040811, (short) 0, (short) 1), 1, "TEST_BUDGET"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM trainer_venue_stock"));
        assertEquals(100_000_000, scalar("SELECT issued_value FROM trainer_venue_daily_budget"));
    }

    @Test void reusedRoundIdentityCannotSwitchPlayerOrTier() throws Exception {
        UUID round = round(PLAYER, 10_000_000);
        assertFalse(TrainerVenueLedger.openRound(round, MAP, CHANNEL, "VenueHost", PLAYER, 10_000_000));
        assertThrows(SQLException.class, () -> TrainerVenueLedger.openRound(round, MAP, CHANNEL, "VenueHost", PLAYER, 50_000_000));
        assertTrue(TrainerVenueLedger.closeRound(round));
        assertThrows(SQLException.class, () -> TrainerVenueLedger.treasury(round, UUID.randomUUID(), MAP, CHANNEL, 10, "TEST"));
    }

    @Test void retryCannotExposeAlreadyReturnedReservation() throws Exception {
        acquire(1_600_000); UUID round = round(null, 0); UUID asset = reserve(round);
        assertEquals(asset, reserve(round)); expose(round, asset);
        assertTrue(TrainerVenueLedger.returnUnclaimed(round, asset, UUID.randomUUID()));
        assertThrows(SQLException.class, () -> reserve(round));
        assertTrue(TrainerVenueLedger.closeRound(round));
        assertEquals(1, TrainerVenueLedger.availableCount(MAP, CHANNEL));
    }

    @Test void paidAdmissionAndRefundEachCommitOnce() throws Exception {
        Character actor = player(); UUID round = round(PLAYER, 10_000_000);
        assertTrue(TrainerVenueLedger.admit(round, actor));
        assertTrue(TrainerVenueLedger.admit(round, actor));
        assertEquals(90_000_000, scalar("SELECT meso FROM characters WHERE id=900001"));
        assertEquals(10_000_000, scalar("SELECT balance FROM trainer_venue_treasury"));
        assertTrue(TrainerVenueLedger.refundBeforePlay(round, actor));
        assertFalse(TrainerVenueLedger.refundBeforePlay(round, actor));
        assertEquals(100_000_000, scalar("SELECT meso FROM characters WHERE id=900001"));
        assertEquals(0, scalar("SELECT balance FROM trainer_venue_treasury"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM trainer_venue_meso_ops"));
    }

    @Test void minimumLongCannotOverflowTreasury() throws Exception {
        UUID round = round(null, 0);
        assertThrows(IllegalArgumentException.class, () -> TrainerVenueLedger.treasury(round, UUID.randomUUID(), MAP, CHANNEL, Long.MIN_VALUE, "TEST"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM trainer_venue_treasury_ops"));
    }

    @Test void inventoryAndClaimRollbackTogetherAndOtherTabsSurvive() throws Exception {
        acquire(1_600_000); UUID round = round(null, 0), asset = reserve(round); expose(round, asset);
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            Item etc = new Item(4000000, (short) 1, (short) 1);
            ItemFactory.INVENTORY.saveInventoryType(List.of(etc), InventoryType.ETC, PLAYER, connection); connection.commit();
            var locked = TrainerVenueLedger.lockExposedAsset(connection, round, asset, MAP, CHANNEL);
            locked.item().setPosition((short) 1);
            ItemFactory.INVENTORY.saveInventoryType(List.of(locked.item()), InventoryType.USE, PLAYER, connection);
            TrainerVenueLedger.claimToHumanInventory(connection, round, asset, PLAYER);
            connection.rollback();
        }
        assertEquals(0, scalar("SELECT COUNT(*) FROM inventoryitems WHERE inventorytype=2"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM inventoryitems WHERE inventorytype=4"));
        assertFalse(TrainerVenueLedger.humanClaimCommitted(round, asset, PLAYER));
        assertEquals(1, scalar("SELECT COUNT(*) FROM trainer_venue_stock WHERE state='EXPOSED'"));
    }

    @Test void hundredConcurrentClaimsHaveOneDurableWinner() throws Exception {
        acquire(1_600_000); UUID round = round(null, 0), asset = reserve(round); expose(round, asset);
        CountDownLatch ready = new CountDownLatch(100), start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int actorId = PLAYER + i;
                futures.add(executor.submit(() -> {
                    ready.countDown(); start.await();
                    try (Connection connection = pool.getConnection()) {
                        connection.setAutoCommit(false);
                        var locked = TrainerVenueLedger.lockExposedAsset(connection, round, asset, MAP, CHANNEL);
                        if (locked == null) { connection.rollback(); return null; }
                        locked.item().setPosition((short) 1);
                        ItemFactory.INVENTORY.saveInventoryType(List.of(locked.item()), InventoryType.USE, actorId, connection);
                        TrainerVenueLedger.claimToHumanInventory(connection, round, asset, actorId);
                        connection.commit(); winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, winners.get());
        assertEquals(1, scalar("SELECT SUM(quantity) FROM inventoryitems"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM trainer_venue_asset_ops WHERE operation_kind='HUMAN_CLAIM'"));
        TrainerVenueLedger.recoverBeforeLogin();
        TrainerVenueLedger.recoverBeforeLogin();
        assertEquals(0, TrainerVenueLedger.availableCount(MAP, CHANNEL));
        assertEquals(1, scalar("SELECT COUNT(*) FROM trainer_venue_stock WHERE state='CLAIMED'"));
    }

    @Test void startupReturnsExposedBotAssetWithoutDuplicatingIt() throws Exception {
        acquire(1_600_000); UUID round = round(null, 0), asset = reserve(round); expose(round, asset);
        TrainerVenueLedger.recoverBeforeLogin(); TrainerVenueLedger.recoverBeforeLogin();
        assertEquals(1, TrainerVenueLedger.availableCount(MAP, CHANNEL));
        assertEquals(1, scalar("SELECT COUNT(*) FROM trainer_venue_asset_ops WHERE operation_kind='RECOVERY_RETURN'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM trainer_venue_rounds WHERE phase='CLOSED'"));
    }

    @Test void preplayRecoveryRefundsAndReturnsEscrowExactlyOnce() throws Exception {
        acquire(1_600_000); UUID round = round(PLAYER, 50_000_000); reserve(round);
        assertTrue(TrainerVenueLedger.admit(round, player()));
        TrainerVenueLedger.recoverBeforeLogin(); TrainerVenueLedger.recoverBeforeLogin();
        assertEquals(100_000_000, scalar("SELECT meso FROM characters WHERE id=900001"));
        assertEquals(0, scalar("SELECT balance FROM trainer_venue_treasury"));
        assertEquals(1, TrainerVenueLedger.availableCount(MAP, CHANNEL));
        assertFalse(TrainerVenueLedger.hasFault(MAP, CHANNEL));
    }

    @Test void interruptedGameplayNeverRefundsPlusReturnsClaimedLoot() throws Exception {
        acquire(1_600_000); UUID round = round(PLAYER, 10_000_000), asset = reserve(round);
        assertTrue(TrainerVenueLedger.admit(round, player())); expose(round, asset);
        TrainerVenueLedger.recoverBeforeLogin(); TrainerVenueLedger.recoverBeforeLogin();
        assertEquals(90_000_000, scalar("SELECT meso FROM characters WHERE id=900001"));
        assertEquals(10_000_000, scalar("SELECT balance FROM trainer_venue_treasury"));
        assertEquals(1, TrainerVenueLedger.availableCount(MAP, CHANNEL));
        assertFalse(TrainerVenueLedger.hasFault(MAP, CHANNEL));
    }

    @Test void conflictingOldTransferStopsTheAffectedTable() throws Exception {
        acquire(1_600_000); UUID round = round(null, 0), asset = reserve(round); expose(round, asset);
        execute("UPDATE trainer_venue_stock SET state='CLAIM_PENDING'");
        TrainerVenueLedger.recoverBeforeLogin();
        assertTrue(TrainerVenueLedger.hasFault(MAP, CHANNEL));
        assertFalse(TrainerVenueLedger.hasFault(100000102, CHANNEL));
        assertEquals(0, TrainerVenueLedger.availableCount(MAP, CHANNEL));
    }

    @Test void repeatedBotRoundsRotateOneFiniteAssetWithoutIncreasingSupply() throws Exception {
        acquire(1_600_000); UUID round = round(null, 0);
        for (int i = 0; i < 20; i++) {
            UUID asset = TrainerVenueLedger.reserve(round, TrainerVenueLedger.stepId(round, "reserve:" + i), 1, 100_000_000).orElseThrow().id();
            assertTrue(TrainerVenueLedger.expose(round, asset, TrainerVenueLedger.stepId(round, "expose:" + i)));
            if (i % 2 == 0) assertTrue(TrainerVenueLedger.claimToBot(round, asset, MAP, CHANNEL, 1234,
                    "VenueWinner", TrainerVenueLedger.stepId(round, "bot-claim:" + i)));
            else assertTrue(TrainerVenueLedger.returnUnclaimed(round, asset, TrainerVenueLedger.stepId(round, "return:" + i)));
        }
        assertTrue(TrainerVenueLedger.closeRound(round));
        assertEquals(1, scalar("SELECT COUNT(*) FROM trainer_venue_stock"));
        assertEquals(1, TrainerVenueLedger.availableCount(MAP, CHANNEL));
        assertEquals(60, scalar("SELECT COUNT(*) FROM trainer_venue_asset_ops"));
    }
    private void personalGrant(String owner) throws Exception {
        List<Item> items = new ArrayList<>(); List<Long> values = new ArrayList<>();
        for (int id : TrainerSocialStock.itemIds(owner)) {
            items.add(new Item(id, (short) 0, (short) 1)); values.add(id == 2049100 ? 50_000_000L : 1_600_000L);
        }
        TrainerVenueLedger.grantPersonalStock(MAP, CHANNEL, owner, items, values);
    }

    @Test void concurrentPersonalGrantsIssueOneCampaignBundle() throws Exception {
        String owner = TrainerSocialStock.name(MAP, CHANNEL, 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) tasks.add(executor.submit(() -> { personalGrant(owner); return null; }));
            for (var task : tasks) task.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertTrue(TrainerVenueLedger.hasPersonalGrant(MAP, CHANNEL, owner));
        assertEquals(3, scalar("SELECT COUNT(*) FROM trainer_venue_stock"));
        assertEquals(0, TrainerVenueLedger.availableCount(MAP, CHANNEL));
        assertEquals(0, scalar("SELECT COUNT(*) FROM trainer_venue_daily_budget"));
    }

    @Test void personalStockCannotBeReservedByAnotherOwnerOrPaidTable() throws Exception {
        String owner = TrainerSocialStock.name(MAP, CHANNEL, 0), other = TrainerSocialStock.name(MAP, CHANNEL, 1);
        personalGrant(owner); personalGrant(other);
        UUID social = UUID.randomUUID();
        assertTrue(TrainerVenueLedger.openSocialRound(social, MAP, CHANNEL, owner, PLAYER));
        assertThrows(SQLException.class, () -> TrainerVenueLedger.reservePersonal(social, UUID.randomUUID(), 1, 100_000_000, other));
        assertEquals(owner, TrainerVenueLedger.reservePersonal(social, UUID.randomUUID(), 1, 100_000_000, owner).orElseThrow().ownerBotName());
        UUID paid = round(PLAYER, 10_000_000);
        assertTrue(TrainerVenueLedger.reserve(paid, UUID.randomUUID(), 1, 100_000_000).isEmpty());
        assertTrue(TrainerVenueLedger.isPaidRound(paid)); assertFalse(TrainerVenueLedger.isPaidRound(social));
    }

    @Test void personalClaimInventoryAndObservedMemoryCommitOrRollbackTogether() throws Exception {
        String owner = TrainerSocialStock.name(MAP, CHANNEL, 0); personalGrant(owner);
        UUID social = UUID.randomUUID(); TrainerVenueLedger.openSocialRound(social, MAP, CHANNEL, owner, PLAYER);
        UUID asset = TrainerVenueLedger.reservePersonal(social, UUID.randomUUID(), 1, 100_000_000, owner).orElseThrow().id();
        expose(social, asset);
        for (boolean commit : new boolean[]{false, true}) {
            try (Connection c = pool.getConnection()) {
                c.setAutoCommit(false);
                var item = TrainerVenueLedger.lockExposedAsset(c, social, asset, MAP, CHANNEL).item(); item.setPosition((short) 1);
                ItemFactory.INVENTORY.saveInventoryType(List.of(item), InventoryType.USE, PLAYER, c);
                TrainerVenueLedger.claimToHumanInventory(c, social, asset, PLAYER);
                TrainerSocialMemory.committedLoss(c, asset, owner, PLAYER, item.getItemId(), true, true);
                TrainerSocialMemory.committedLoss(c, asset, owner, PLAYER, item.getItemId(), true, true);
                if (commit) c.commit(); else c.rollback();
            }
            assertEquals(commit ? 1 : 0, scalar("SELECT COUNT(*) FROM inventoryitems"));
            assertEquals(commit ? 1 : 0, scalar("SELECT COUNT(*) FROM trainer_social_incidents"));
        }
        assertEquals(1, scalar("SELECT losses FROM trainer_social_memory"));
        assertEquals(80, scalar("SELECT suspicion FROM trainer_social_memory"));
        TrainerVenueLedger.recoverBeforeLogin(); personalGrant(owner);
        assertEquals(3, scalar("SELECT COUNT(*) FROM trainer_venue_stock"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM trainer_venue_stock WHERE state='AVAILABLE'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM trainer_venue_stock WHERE state='CLAIMED'"));
        assertEquals(100_000_000, scalar("SELECT meso FROM characters WHERE id=900001"));
    }

    @Test void personalExposureRecoveryReturnsOnlyTheUnclaimedAsset() throws Exception {
        String owner = TrainerSocialStock.name(MAP, CHANNEL, 0); personalGrant(owner);
        UUID social = UUID.randomUUID(); TrainerVenueLedger.openSocialRound(social, MAP, CHANNEL, owner, PLAYER);
        UUID asset = TrainerVenueLedger.reservePersonal(social, UUID.randomUUID(), 1, 100_000_000, owner).orElseThrow().id();
        expose(social, asset);
        TrainerVenueLedger.returnPersonalUnclaimed(social); TrainerVenueLedger.returnPersonalUnclaimed(social);
        assertTrue(TrainerVenueLedger.closeRound(social)); TrainerVenueLedger.recoverBeforeLogin();
        assertEquals(3, scalar("SELECT COUNT(*) FROM trainer_venue_stock WHERE state='AVAILABLE'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM trainer_venue_asset_ops WHERE operation_kind='RECOVERY_RETURN'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM trainer_venue_meso_ops"));
    }

    @Test void personalInvitationCooldownSurvivesRuntimeReset() throws Exception {
        String owner = TrainerSocialStock.name(MAP, CHANNEL, 0);
        UUID first = UUID.randomUUID(); assertTrue(TrainerVenueLedger.openSocialRound(first, MAP, CHANNEL, owner, PLAYER));
        assertTrue(TrainerVenueLedger.closeRound(first));
        assertFalse(TrainerVenueLedger.openSocialRound(UUID.randomUUID(), MAP, CHANNEL, owner, PLAYER));
        execute("UPDATE trainer_venue_rounds SET opened_at_ms=opened_at_ms-61000");
        assertTrue(TrainerVenueLedger.openSocialRound(UUID.randomUUID(), MAP, CHANNEL, owner, PLAYER));
    }

    @Test void unseenCommittedLossNeverCreatesAnAccusedActor() throws Exception {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            TrainerSocialMemory.committedLoss(c, UUID.randomUUID(), "Observer", null, 2049100, true, true);
            c.commit();
        }
        assertEquals(0, scalar("SELECT COUNT(*) FROM trainer_social_memory"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM trainer_social_incidents WHERE actor_character_id IS NULL AND evidence=0"));
    }

}

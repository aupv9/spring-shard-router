package org.springframework.boot.starter.sharding.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.starter.sharding.core.HashShardRouter;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit/integration tests for {@link ShardJdbcTemplate} using an in-memory H2 database.
 *
 * <p>Covers:
 * <ul>
 *   <li>update / batchUpdate routing</li>
 *   <li>queryForObject / queryForList / queryForMap / query routing</li>
 *   <li>Save/restore context: shard key is preserved after each call</li>
 *   <li>Exception propagation: DataAccessException re-thrown unchanged</li>
 *   <li>Cross-shard isolation: writes on shard-0 are invisible on shard-1</li>
 * </ul>
 */
class ShardJdbcTemplateTest {

    private DataSource ds0;
    private DataSource ds1;
    private ShardJdbcTemplate shardTemplate;

    // key → shard mapping using two H2 databases
    // even keys → shard-0, odd keys → shard-1
    private static final long KEY_ON_SHARD_0 = 2L;   // 2 % 2 == 0
    private static final long KEY_ON_SHARD_1 = 3L;   // 3 % 2 == 1

    @BeforeEach
    void setUp() {
        ds0 = h2DataSource("jt_db0");
        ds1 = h2DataSource("jt_db1");

        List<Shard> shards = List.of(
            Shard.of("shard-0", 0, ds0),
            Shard.of("shard-1", 1, ds1)
        );

        shardTemplate = new ShardJdbcTemplate(
            new org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy(
                new RoutingDataSource(new HashShardRouter(shards))
            )
        );

        initSchema(ds0);
        initSchema(ds1);
    }

    @AfterEach
    void tearDown() {
        ShardContext.clear();
        dropSchema(ds0);
        dropSchema(ds1);
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    @Test
    void update_insertsRowOnCorrectShard() {
        int rows = shardTemplate.update(KEY_ON_SHARD_0,
            "INSERT INTO accounts (id, balance) VALUES (?, ?)", 100L, 500);

        assertThat(rows).isEqualTo(1);
        // Row must exist on shard-0
        Long balance = new JdbcTemplate(ds0)
            .queryForObject("SELECT balance FROM accounts WHERE id=100", Long.class);
        assertThat(balance).isEqualTo(500L);
        // Row must NOT exist on shard-1
        Long count1 = new JdbcTemplate(ds1)
            .queryForObject("SELECT COUNT(*) FROM accounts WHERE id=100", Long.class);
        assertThat(count1).isEqualTo(0L);
    }

    @Test
    void update_differentKey_routesToDifferentShard() {
        shardTemplate.update(KEY_ON_SHARD_0,
            "INSERT INTO accounts (id, balance) VALUES (?, ?)", 200L, 100);
        shardTemplate.update(KEY_ON_SHARD_1,
            "INSERT INTO accounts (id, balance) VALUES (?, ?)", 201L, 200);

        // Each shard has its own row
        Long b0 = new JdbcTemplate(ds0).queryForObject(
            "SELECT balance FROM accounts WHERE id=200", Long.class);
        Long b1 = new JdbcTemplate(ds1).queryForObject(
            "SELECT balance FROM accounts WHERE id=201", Long.class);
        assertThat(b0).isEqualTo(100L);
        assertThat(b1).isEqualTo(200L);
    }

    // -------------------------------------------------------------------------
    // batchUpdate
    // -------------------------------------------------------------------------

    @Test
    void batchUpdate_insertsAllRowsOnCorrectShard() {
        List<Object[]> batch = List.of(
            new Object[]{300L, 10},
            new Object[]{302L, 20},
            new Object[]{304L, 30}
        );

        int[] counts = shardTemplate.batchUpdate(KEY_ON_SHARD_0,
            "INSERT INTO accounts (id, balance) VALUES (?, ?)", batch);

        assertThat(counts).hasSize(3);
        Long total = new JdbcTemplate(ds0)
            .queryForObject("SELECT COUNT(*) FROM accounts WHERE id IN (300,302,304)", Long.class);
        assertThat(total).isEqualTo(3L);
    }

    // -------------------------------------------------------------------------
    // queryForObject
    // -------------------------------------------------------------------------

    @Test
    void queryForObject_readsFromCorrectShard() {
        new JdbcTemplate(ds0).update(
            "INSERT INTO accounts (id, balance) VALUES (?, ?)", 400L, 999);

        Long balance = shardTemplate.queryForObject(KEY_ON_SHARD_0,
            "SELECT balance FROM accounts WHERE id=?", Long.class, 400L);

        assertThat(balance).isEqualTo(999L);
    }

    @Test
    void queryForObject_rowMapper_readsFromCorrectShard() {
        new JdbcTemplate(ds1).update(
            "INSERT INTO accounts (id, balance) VALUES (?, ?)", 401L, 777);

        Long balance = shardTemplate.queryForObject(KEY_ON_SHARD_1,
            "SELECT balance FROM accounts WHERE id=?",
            (rs, n) -> rs.getLong("balance"), 401L);

        assertThat(balance).isEqualTo(777L);
    }

    // -------------------------------------------------------------------------
    // queryForList / queryForMap
    // -------------------------------------------------------------------------

    @Test
    void queryForList_returnsRowsFromCorrectShard() {
        new JdbcTemplate(ds0).update("INSERT INTO accounts (id, balance) VALUES (500, 50)");
        new JdbcTemplate(ds0).update("INSERT INTO accounts (id, balance) VALUES (502, 60)");

        List<Map<String, Object>> rows = shardTemplate.queryForList(KEY_ON_SHARD_0,
            "SELECT * FROM accounts WHERE id IN (500, 502)");

        assertThat(rows).hasSize(2);
    }

    @Test
    void queryForMap_returnsRowFromCorrectShard() {
        new JdbcTemplate(ds1).update("INSERT INTO accounts (id, balance) VALUES (601, 42)");

        Map<String, Object> row = shardTemplate.queryForMap(KEY_ON_SHARD_1,
            "SELECT * FROM accounts WHERE id=?", 601L);

        assertThat(row).containsEntry("ID", 601L);
    }

    // -------------------------------------------------------------------------
    // Context save/restore (Gap 2.1 fix verification)
    // -------------------------------------------------------------------------

    @Test
    void contextIsRestoredAfterEachCall_noOuterKey() {
        // No outer key — after each call context should be null
        shardTemplate.update(KEY_ON_SHARD_0,
            "INSERT INTO accounts (id, balance) VALUES (700, 1)");

        assertThat(ShardContext.get())
            .as("ShardContext must be null after call when no outer key was set")
            .isNull();
    }

    @Test
    void contextIsRestoredAfterEachCall_withOuterKey() {
        // Simulate an outer @Transactional that set the key before calling the template
        ShardContext.set(KEY_ON_SHARD_0);

        shardTemplate.update(KEY_ON_SHARD_0,
            "INSERT INTO accounts (id, balance) VALUES (800, 1)");

        assertThat(ShardContext.get())
            .as("ShardContext must be restored to the outer key after nested call")
            .isEqualTo(KEY_ON_SHARD_0);
    }

    @Test
    void multipleCallsInSequence_contextRestoredBetweenEachCall() {
        shardTemplate.update(KEY_ON_SHARD_0,
            "INSERT INTO accounts (id, balance) VALUES (900, 1)");
        assertThat(ShardContext.get()).isNull();

        shardTemplate.update(KEY_ON_SHARD_1,
            "INSERT INTO accounts (id, balance) VALUES (901, 2)");
        assertThat(ShardContext.get()).isNull();

        // Both rows visible on their respective shards
        Long c0 = new JdbcTemplate(ds0)
            .queryForObject("SELECT COUNT(*) FROM accounts WHERE id=900", Long.class);
        Long c1 = new JdbcTemplate(ds1)
            .queryForObject("SELECT COUNT(*) FROM accounts WHERE id=901", Long.class);
        assertThat(c0).isEqualTo(1L);
        assertThat(c1).isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Exception propagation
    // -------------------------------------------------------------------------

    @Test
    void queryForObject_throwsDataAccessException_whenRowNotFound() {
        assertThatThrownBy(() ->
            shardTemplate.queryForObject(KEY_ON_SHARD_0,
                "SELECT balance FROM accounts WHERE id=?", Long.class, -1L))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void contextIsRestoredEvenWhenExceptionThrown() {
        assertThatThrownBy(() ->
            shardTemplate.queryForObject(KEY_ON_SHARD_0,
                "SELECT balance FROM accounts WHERE id=?", Long.class, -1L))
            .isInstanceOf(EmptyResultDataAccessException.class);

        assertThat(ShardContext.get())
            .as("ShardContext must be null even when an exception was thrown")
            .isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static DataSource h2DataSource(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    private static void initSchema(DataSource ds) {
        new JdbcTemplate(ds).execute(
            "CREATE TABLE IF NOT EXISTS accounts (id BIGINT PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0)"
        );
    }

    private static void dropSchema(DataSource ds) {
        new JdbcTemplate(ds).execute("DROP TABLE IF EXISTS accounts");
    }
}

package org.springframework.boot.starter.sharding.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.starter.sharding.core.HashShardRouter;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ShardScatterGatherTemplateTest {

    private DataSource ds0;
    private DataSource ds1;
    private ShardScatterGatherTemplate template;

    @BeforeEach
    void setUp() {
        ds0 = h2DataSource("sg_db0");
        ds1 = h2DataSource("sg_db1");

        initSchema(ds0, "a", "b");   // shard 0 has rows 'a', 'b'
        initSchema(ds1, "c", "d");   // shard 1 has rows 'c', 'd'

        List<Shard> shards = List.of(
            Shard.of("shard-0", 0, ds0),
            Shard.of("shard-1", 1, ds1)
        );
        ShardRouter router = new HashShardRouter(shards);
        template = new ShardScatterGatherTemplate(router);
    }

    @AfterEach
    void tearDown() {
        new JdbcTemplate(ds0).execute("DROP TABLE IF EXISTS items");
        new JdbcTemplate(ds1).execute("DROP TABLE IF EXISTS items");
    }

    // ── queryAllShards (RowMapper) ─────────────────────────────────────────────

    @Test
    void shouldMergeResultsFromAllShards() {
        List<String> values = template.queryAllShards(
            "SELECT value FROM items ORDER BY value",
            (rs, rowNum) -> rs.getString("value")
        );
        assertEquals(4, values.size());
        assertTrue(values.containsAll(List.of("a", "b", "c", "d")));
    }

    @Test
    void shouldReturnMapResultsFromAllShards() {
        List<Map<String, Object>> rows = template.queryAllShards("SELECT value FROM items");
        assertEquals(4, rows.size());
    }

    // ── queryAllShardsSorted ──────────────────────────────────────────────────

    @Test
    void shouldMergeAndSortResultsGlobally() {
        List<String> sorted = template.<String>queryAllShardsSorted(
            "SELECT value FROM items",
            (rs, row) -> rs.getString("value"),
            Comparator.naturalOrder()
        );
        assertEquals(List.of("a", "b", "c", "d"), sorted);
    }

    // ── queryAllShardsTopN ────────────────────────────────────────────────────

    @Test
    void shouldReturnTopNResultsAfterGlobalSort() {
        List<String> top2 = template.<String>queryAllShardsTopN(
            "SELECT value FROM items",
            (rs, row) -> rs.getString("value"),
            Comparator.naturalOrder(),
            2
        );
        assertEquals(List.of("a", "b"), top2);
    }

    // ── countAllShards ────────────────────────────────────────────────────────

    @Test
    void shouldSumCountsAcrossAllShards() {
        long total = template.countAllShards("SELECT COUNT(*) FROM items");
        assertEquals(4L, total);
    }

    // ── scatter (custom) ──────────────────────────────────────────────────────

    @Test
    void shouldExecuteCustomOperationOnAllShards() {
        AtomicInteger queryCount = new AtomicInteger(0);
        List<String> results = template.scatter(jdbc -> {
            queryCount.incrementAndGet();
            return jdbc.query("SELECT value FROM items", (rs, row) -> rs.getString("value"));
        });
        assertEquals(2, queryCount.get(), "Custom operation must run on both shards");
        assertEquals(4, results.size());
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test
    void shouldThrowScatterGatherExceptionOnShardFailure() {
        assertThrows(ShardScatterGatherTemplate.ShardScatterGatherException.class, () ->
            template.queryAllShards("SELECT value FROM non_existent_table",
                (rs, row) -> rs.getString("value"))
        );
    }

    // ── custom executor ───────────────────────────────────────────────────────

    @Test
    void shouldWorkWithCustomExecutor() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Shard> shards = List.of(
            Shard.of("shard-0", 0, ds0),
            Shard.of("shard-1", 1, ds1)
        );
        ShardRouter router = new HashShardRouter(shards);
        ShardScatterGatherTemplate customTemplate = new ShardScatterGatherTemplate(router, executor);

        long total = customTemplate.countAllShards("SELECT COUNT(*) FROM items");
        assertEquals(4L, total);
        executor.shutdown();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DataSource h2DataSource(String dbName) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        // NON_KEYWORDS=VALUE: 'value' is a reserved word in modern H2; the test
        // schema uses it as a column name, so exclude it from the keyword list.
        ds.setUrl("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=VALUE");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    private static void initSchema(DataSource ds, String... values) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS items (value VARCHAR(10))");
        for (String v : values) {
            jdbc.update("INSERT INTO items VALUES (?)", v);
        }
    }
}

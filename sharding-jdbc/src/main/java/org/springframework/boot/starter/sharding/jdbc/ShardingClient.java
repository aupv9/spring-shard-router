package org.springframework.boot.starter.sharding.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Unified client-side sharding facade.
 *
 * <p>Replaces the three-way split between {@link ShardJdbcTemplate},
 * {@link org.springframework.boot.starter.sharding.jpa.ShardEntityManager}, and
 * {@link ShardScatterGatherTemplate} with a single, fluent entry point.
 *
 * <h2>Usage</h2>
 *
 * <h3>Single-shard (most operations)</h3>
 * <pre>{@code
 * // Query
 * BigDecimal balance = client.onShard(accountId)
 *     .queryForObject("SELECT balance FROM accounts WHERE account_id=?",
 *                     BigDecimal.class, accountId);
 *
 * // Update
 * client.onShard(accountId)
 *     .update("UPDATE accounts SET balance=balance-? WHERE account_id=?",
 *             amount, accountId);
 *
 * // Escape hatch — full JdbcTemplate access with context already set
 * client.onShard(accountId).execute(jdbc -> {
 *     BigDecimal b = jdbc.queryForObject(...);
 *     jdbc.update(...);
 *     return b;
 * });
 * }</pre>
 *
 * <h3>Cross-shard fan-out</h3>
 * <pre>{@code
 * // All shards
 * List<Transaction> pending = client.allShards()
 *     .query("SELECT * FROM transactions WHERE status='PENDING'", txMapper);
 *
 * // Subset of shards (shard affinity)
 * List<Transaction> regional = client.onShards(0, 1)
 *     .query("SELECT * FROM transactions WHERE region='EU'", txMapper);
 * }</pre>
 *
 * <h2>When to use which entry point</h2>
 * <table border="1" cellpadding="4">
 *   <tr><th>Entry point</th><th>Use case</th></tr>
 *   <tr><td>{@link #onShard(long)}</td><td>Normal business operations — single account/entity</td></tr>
 *   <tr><td>{@link #allShards()}</td><td>Admin queries, global aggregations, scatter-gather</td></tr>
 *   <tr><td>{@link #onShards(int...)}</td><td>Regional queries when shard affinity is known</td></tr>
 * </table>
 *
 * <p>This interface is registered as a Spring bean by
 * {@link org.springframework.boot.starter.sharding.autoconfigure.ShardingAutoConfiguration}
 * when {@code sharding.enabled=true}.
 */
public interface ShardingClient {

    /**
     * Bind a shard key — all operations on the returned {@link BoundShardOps} route to
     * the shard that owns {@code shardKey}.
     *
     * @param shardKey the routing key (e.g. {@code accountId})
     */
    BoundShardOps onShard(long shardKey);

    /**
     * Fan-out to every configured shard in parallel.
     */
    ScatterOps allShards();

    /**
     * Fan-out to a specific subset of shards by zero-based index.
     * Use when you have shard affinity knowledge (e.g. regional partitioning).
     *
     * @param shardIndices zero-based indices of shards to query
     */
    ScatterOps onShards(int... shardIndices);

    // =========================================================================
    // Single-shard operations
    // =========================================================================

    /**
     * Operations bound to a single resolved shard.
     */
    interface BoundShardOps {

        /**
         * Execute a SQL query expecting a single result of type {@code T}.
         */
        <T> T queryForObject(String sql, Class<T> type, Object... args);

        /**
         * Execute a SQL query with a custom row mapper, expecting a single result.
         */
        <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args);

        /**
         * Execute a SQL query and return all results as a list.
         */
        <T> List<T> queryList(String sql, RowMapper<T> rowMapper, Object... args);

        /**
         * Execute a SQL query and return all results as maps.
         */
        List<Map<String, Object>> queryList(String sql, Object... args);

        /**
         * Execute an INSERT, UPDATE, or DELETE.
         *
         * @return number of rows affected
         */
        int update(String sql, Object... args);

        /**
         * Execute a batch INSERT, UPDATE, or DELETE.
         *
         * @param sql       SQL template with {@code ?} placeholders
         * @param batchArgs list of argument arrays — one per batch row
         * @return row counts for each batch element
         */
        int[] batchUpdate(String sql, List<Object[]> batchArgs);

        /**
         * Escape hatch: run arbitrary code against the shard's {@link JdbcTemplate}
         * with {@link org.springframework.boot.starter.sharding.core.ShardContext} already set.
         * The context is restored to its previous state after the lambda returns,
         * even if it throws.
         *
         * @param action receives the shard's underlying {@link JdbcTemplate}
         * @param <T>    return type
         * @return the value returned by {@code action}
         */
        <T> T execute(Function<JdbcTemplate, T> action);

        /**
         * Index of the shard that this key resolved to.
         */
        int shardIndex();

        /**
         * Name of the shard that this key resolved to.
         */
        String shardName();
    }

    // =========================================================================
    // Cross-shard scatter operations
    // =========================================================================

    /**
     * Operations that fan out across multiple shards in parallel.
     */
    interface ScatterOps {

        /**
         * Run {@code sql} on every targeted shard and return merged results.
         */
        <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args);

        /**
         * Run {@code sql} on every targeted shard and return merged rows as maps.
         */
        List<Map<String, Object>> query(String sql, Object... args);

        /**
         * Run {@code sql} on every targeted shard, merge, then sort globally.
         */
        <T> List<T> querySorted(String sql, RowMapper<T> rowMapper,
                                 Comparator<T> order, Object... args);

        /**
         * Run {@code sql} on every targeted shard, merge, sort, return top {@code n}.
         */
        <T> List<T> queryTopN(String sql, RowMapper<T> rowMapper,
                               Comparator<T> order, int n, Object... args);

        /**
         * Run a COUNT query on every targeted shard and return the sum.
         */
        long count(String sql, Object... args);

        /**
         * Run a custom operation on every targeted shard.
         *
         * @param operation receives a {@link JdbcTemplate} per shard; results are merged
         */
        <T> List<T> scatter(Function<JdbcTemplate, List<T>> operation);
    }
}

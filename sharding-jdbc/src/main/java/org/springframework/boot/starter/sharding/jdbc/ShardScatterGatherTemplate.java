package org.springframework.boot.starter.sharding.jdbc;

import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Scatter-gather template for cross-shard queries.
 *
 * <p>Sends a query to ALL shards in parallel, then merges the results.
 * Use this only when you genuinely need data from every shard — for example,
 * admin dashboards, global reporting, or finding a record whose shard key is unknown.
 *
 * <p>For normal business operations, always prefer {@link ShardJdbcTemplate} which
 * routes to a single shard. Cross-shard queries are expensive and do not scale
 * linearly with shard count.
 *
 * <p><b>Phase 3.1 additions:</b>
 * <ul>
 *   <li>{@link #queryShards(List, String, RowMapper, Object...)} — shard affinity hints</li>
 *   <li>{@link #queryAllShardsPaged(String, RowMapper, Comparator, int, int, Object...)} — memory-efficient pagination</li>
 * </ul>
 *
 * <p>Thread safety: instances are stateless and safe to share.
 */
public class ShardScatterGatherTemplate {

    private final List<JdbcTemplate> shardTemplates;
    private final ShardRouter shardRouter;
    private final ExecutorService executor;

    public ShardScatterGatherTemplate(ShardRouter shardRouter) {
        this(shardRouter, Executors.newVirtualThreadPerTaskExecutor());
    }

    public ShardScatterGatherTemplate(ShardRouter shardRouter, ExecutorService executor) {
        this.shardRouter = shardRouter;
        this.executor = executor;
        this.shardTemplates = new ArrayList<>(shardRouter.getShardCount());
        for (int i = 0; i < shardRouter.getShardCount(); i++) {
            Shard shard = shardRouter.getShard(i);
            this.shardTemplates.add(new JdbcTemplate(shard.dataSource()));
        }
    }

    // -------------------------------------------------------------------------
    // All-shards operations (original API)
    // -------------------------------------------------------------------------

    /**
     * Run {@code sql} on every shard and return a merged, unsorted list.
     */
    public <T> List<T> queryAllShards(String sql, RowMapper<T> rowMapper, Object... args) {
        return scatter(template -> template.query(sql, rowMapper, args));
    }

    /**
     * Run {@code sql} on every shard and return merged rows as maps.
     */
    public List<Map<String, Object>> queryAllShards(String sql, Object... args) {
        return scatter(template -> template.queryForList(sql, args));
    }

    /**
     * Run {@code sql} on every shard, merge results, then sort by {@code comparator}.
     */
    public <T> List<T> queryAllShardsSorted(String sql, RowMapper<T> rowMapper,
                                             Comparator<T> comparator, Object... args) {
        List<T> merged = queryAllShards(sql, rowMapper, args);
        merged.sort(comparator);
        return merged;
    }

    /**
     * Run {@code sql} on every shard, merge results, and return the first {@code limit} rows
     * after sorting. Useful for global "top N" queries.
     */
    public <T> List<T> queryAllShardsTopN(String sql, RowMapper<T> rowMapper,
                                           Comparator<T> comparator, int limit, Object... args) {
        return queryAllShardsSorted(sql, rowMapper, comparator, args).stream()
            .limit(limit)
            .toList();
    }

    /**
     * Count rows matching {@code sql} across ALL shards and return the total.
     */
    public long countAllShards(String sql, Object... args) {
        return scatter(template -> {
            Long count = template.queryForObject(sql, Long.class, args);
            return List.of(count != null ? count : 0L);
        }).stream().mapToLong(Long::longValue).sum();
    }

    // -------------------------------------------------------------------------
    // Phase 3.1: Shard affinity — query only a subset of shards
    // -------------------------------------------------------------------------

    /**
     * Run {@code sql} on a specific subset of shards identified by their zero-based indices.
     *
     * <p>Use this when you have shard affinity knowledge — for example, if all accounts
     * in a region are on shards 0 and 1, pass {@code List.of(0, 1)} to avoid hitting
     * shards 2–N unnecessarily.
     *
     * @param shardIndices zero-based indices of shards to query; must be non-empty
     * @param sql          SQL to execute on each selected shard
     * @param rowMapper    result mapper
     * @param args         SQL bind parameters
     * @param <T>          result row type
     * @return merged, unsorted results from the selected shards
     */
    public <T> List<T> queryShards(List<Integer> shardIndices, String sql,
                                    RowMapper<T> rowMapper, Object... args) {
        if (shardIndices == null || shardIndices.isEmpty()) {
            throw new IllegalArgumentException("shardIndices must not be empty");
        }
        List<JdbcTemplate> selected = shardIndices.stream()
            .map(i -> {
                if (i < 0 || i >= shardTemplates.size()) {
                    throw new IllegalArgumentException("Invalid shard index: " + i);
                }
                return shardTemplates.get(i);
            })
            .collect(Collectors.toList());

        return scatterTo(selected, template -> template.query(sql, rowMapper, args));
    }

    /**
     * Run {@code sql} on a subset of shards and return merged rows as maps.
     */
    public List<Map<String, Object>> queryShards(List<Integer> shardIndices,
                                                  String sql, Object... args) {
        if (shardIndices == null || shardIndices.isEmpty()) {
            throw new IllegalArgumentException("shardIndices must not be empty");
        }
        List<JdbcTemplate> selected = shardIndices.stream()
            .map(i -> shardTemplates.get(i))
            .collect(Collectors.toList());
        return scatterTo(selected, template -> template.queryForList(sql, args));
    }

    // -------------------------------------------------------------------------
    // Phase 3.1: Memory-efficient cross-shard pagination
    // -------------------------------------------------------------------------

    /**
     * Cross-shard paginated query.
     *
     * <p>Pushes {@code LIMIT (pageNumber + 1) * pageSize} down to each shard to reduce
     * data transfer, then merges, sorts globally, and returns the requested page.
     * This is O(N * pageSize) in memory — far cheaper than a full scatter-gather for
     * large tables, but still not O(1). For high-cardinality pagination consider
     * cursor-based approaches.
     *
     * @param sql        SQL with optional {@code LIMIT ?} placeholder at the end;
     *                   if the SQL already contains {@code LIMIT} this method appends another
     *                   — callers should use the version <em>without</em> a trailing LIMIT.
     * @param rowMapper  result mapper
     * @param comparator global sort order (applied after merging all shard results)
     * @param pageSize   number of rows per page
     * @param pageNumber zero-based page number
     * @param args       SQL bind parameters (excluding the appended limit value)
     * @param <T>        result row type
     * @return one page of globally sorted results
     */
    public <T> List<T> queryAllShardsPaged(String sql, RowMapper<T> rowMapper,
                                            Comparator<T> comparator,
                                            int pageSize, int pageNumber,
                                            Object... args) {
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
        if (pageNumber < 0) throw new IllegalArgumentException("pageNumber must be >= 0");

        // Each shard returns enough rows to cover the full page after merge
        int perShardLimit = (pageNumber + 1) * pageSize;
        String pagedSql = sql + " LIMIT " + perShardLimit;

        List<T> merged = scatter(template -> template.query(pagedSql, rowMapper, args));
        merged.sort(comparator);

        int fromIndex = pageNumber * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, merged.size());
        if (fromIndex >= merged.size()) return List.of();
        return merged.subList(fromIndex, toIndex);
    }

    // -------------------------------------------------------------------------
    // Core scatter helpers
    // -------------------------------------------------------------------------

    /**
     * Run a custom operation on every shard and return a merged result list.
     * The provided function receives a plain {@link JdbcTemplate} bound to a single shard.
     *
     * <pre>{@code
     * List<Long> counts = scatter.scatter(t -> t.queryForList(
     *     "SELECT account_id FROM accounts WHERE status = 'ACTIVE'", Long.class));
     * }</pre>
     */
    public <T> List<T> scatter(Function<JdbcTemplate, List<T>> operation) {
        return scatterTo(shardTemplates, operation);
    }

    private <T> List<T> scatterTo(List<JdbcTemplate> templates,
                                   Function<JdbcTemplate, List<T>> operation) {
        List<Callable<List<T>>> tasks = templates.stream()
            .<Callable<List<T>>>map(template -> () -> operation.apply(template))
            .toList();

        try {
            List<Future<List<T>>> futures = executor.invokeAll(tasks);
            List<T> merged = new ArrayList<>();
            for (Future<List<T>> future : futures) {
                merged.addAll(future.get());
            }
            return merged;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShardScatterGatherException("Scatter-gather interrupted", e);
        } catch (ExecutionException e) {
            throw new ShardScatterGatherException("Scatter-gather failed on one or more shards", e.getCause());
        }
    }

    public static class ShardScatterGatherException extends RuntimeException {
        public ShardScatterGatherException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

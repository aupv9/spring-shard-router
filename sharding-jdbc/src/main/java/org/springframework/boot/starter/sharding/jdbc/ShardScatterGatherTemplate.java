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
 * <p>Thread safety: instances are stateless and safe to share.
 */
public class ShardScatterGatherTemplate {

    private final List<JdbcTemplate> shardTemplates;
    private final ExecutorService executor;

    public ShardScatterGatherTemplate(ShardRouter shardRouter) {
        this(shardRouter, Executors.newVirtualThreadPerTaskExecutor());
    }

    public ShardScatterGatherTemplate(ShardRouter shardRouter, ExecutorService executor) {
        this.executor = executor;
        this.shardTemplates = new ArrayList<>(shardRouter.getShardCount());
        for (int i = 0; i < shardRouter.getShardCount(); i++) {
            Shard shard = shardRouter.getShard(i);
            this.shardTemplates.add(new JdbcTemplate(shard.dataSource()));
        }
    }

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
     * Run a custom operation on every shard and return a merged result list.
     * The provided function receives a plain {@link JdbcTemplate} bound to a single shard.
     *
     * <pre>{@code
     * List<Long> counts = scatter.scatter(t -> t.queryForList(
     *     "SELECT account_id FROM accounts WHERE status = 'ACTIVE'", Long.class));
     * }</pre>
     */
    public <T> List<T> scatter(Function<JdbcTemplate, List<T>> operation) {
        List<Callable<List<T>>> tasks = shardTemplates.stream()
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

    /**
     * Count rows matching {@code sql} across ALL shards and return the total.
     */
    public long countAllShards(String sql, Object... args) {
        return scatter(template -> {
            Long count = template.queryForObject(sql, Long.class, args);
            return List.of(count != null ? count : 0L);
        }).stream().mapToLong(Long::longValue).sum();
    }

    public static class ShardScatterGatherException extends RuntimeException {
        public ShardScatterGatherException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

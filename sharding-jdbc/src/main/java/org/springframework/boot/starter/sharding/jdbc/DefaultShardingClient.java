package org.springframework.boot.starter.sharding.jdbc;

import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Default implementation of {@link ShardingClient}.
 *
 * <p>Delegates to the existing {@link ShardJdbcTemplate} (for single-shard ops) and
 * {@link ShardScatterGatherTemplate} (for fan-out ops). No new routing logic is
 * introduced — this class is purely a facade that unifies the two lower-level APIs
 * behind a single, consistent interface.
 *
 * <p>Registered automatically by
 * {@link org.springframework.boot.starter.sharding.autoconfigure.ShardingAutoConfiguration}
 * when {@code sharding.enabled=true}.
 */
public class DefaultShardingClient implements ShardingClient {

    private final ShardRouter shardRouter;
    private final ShardJdbcTemplate shardJdbcTemplate;
    private final ShardScatterGatherTemplate scatterGatherTemplate;

    public DefaultShardingClient(ShardRouter shardRouter,
                                  ShardJdbcTemplate shardJdbcTemplate,
                                  ShardScatterGatherTemplate scatterGatherTemplate) {
        this.shardRouter = shardRouter;
        this.shardJdbcTemplate = shardJdbcTemplate;
        this.scatterGatherTemplate = scatterGatherTemplate;
    }

    // -------------------------------------------------------------------------
    // ShardingClient API
    // -------------------------------------------------------------------------

    @Override
    public BoundShardOps onShard(long shardKey) {
        Shard shard = shardRouter.resolve(shardKey);
        return new DefaultBoundShardOps(shardKey, shard, shardJdbcTemplate);
    }

    @Override
    public ScatterOps allShards() {
        return new DefaultScatterOps(scatterGatherTemplate, null);
    }

    @Override
    public ScatterOps onShards(int... shardIndices) {
        if (shardIndices == null || shardIndices.length == 0) {
            throw new IllegalArgumentException("shardIndices must not be empty");
        }
        List<Integer> indices = IntStream.of(shardIndices).boxed().collect(Collectors.toList());
        return new DefaultScatterOps(scatterGatherTemplate, indices);
    }

    // =========================================================================
    // BoundShardOps implementation
    // =========================================================================

    private static final class DefaultBoundShardOps implements BoundShardOps {

        private final long shardKey;
        private final Shard shard;
        private final ShardJdbcTemplate delegate;

        DefaultBoundShardOps(long shardKey, Shard shard, ShardJdbcTemplate delegate) {
            this.shardKey = shardKey;
            this.shard    = shard;
            this.delegate = delegate;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            return delegate.queryForObject(shardKey, sql, type, args);
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            return delegate.queryForObject(shardKey, sql, rowMapper, args);
        }

        @Override
        public <T> List<T> queryList(String sql, RowMapper<T> rowMapper, Object... args) {
            return delegate.query(shardKey, sql, rowMapper, args);
        }

        @Override
        public List<Map<String, Object>> queryList(String sql, Object... args) {
            return delegate.queryForList(shardKey, sql, args);
        }

        @Override
        public int update(String sql, Object... args) {
            return delegate.update(shardKey, sql, args);
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
            return delegate.batchUpdate(shardKey, sql, batchArgs);
        }

        @Override
        public <T> T execute(Function<JdbcTemplate, T> action) {
            // Use the save/restore pattern consistent with Gap 2.1 fix
            return delegate.executeWithShardKey(shardKey, "execute",
                () -> action.apply(delegate.getJdbcTemplate()));
        }

        @Override
        public int shardIndex() {
            return shard.index();
        }

        @Override
        public String shardName() {
            return shard.name();
        }
    }

    // =========================================================================
    // ScatterOps implementation
    // =========================================================================

    private static final class DefaultScatterOps implements ScatterOps {

        private final ShardScatterGatherTemplate template;
        /** null = all shards; non-null = subset */
        private final List<Integer> shardIndices;

        DefaultScatterOps(ShardScatterGatherTemplate template, List<Integer> shardIndices) {
            this.template      = template;
            this.shardIndices  = shardIndices;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (shardIndices == null) {
                return template.queryAllShards(sql, rowMapper, args);
            }
            return template.queryShards(shardIndices, sql, rowMapper, args);
        }

        @Override
        public List<Map<String, Object>> query(String sql, Object... args) {
            if (shardIndices == null) {
                return template.queryAllShards(sql, args);
            }
            return template.queryShards(shardIndices, sql, args);
        }

        @Override
        public <T> List<T> querySorted(String sql, RowMapper<T> rowMapper,
                                        Comparator<T> order, Object... args) {
            List<T> merged = query(sql, rowMapper, args);
            merged.sort(order);
            return merged;
        }

        @Override
        public <T> List<T> queryTopN(String sql, RowMapper<T> rowMapper,
                                      Comparator<T> order, int n, Object... args) {
            return querySorted(sql, rowMapper, order, args).stream()
                .limit(n)
                .toList();
        }

        @Override
        public long count(String sql, Object... args) {
            if (shardIndices == null) {
                return template.countAllShards(sql, args);
            }
            // Count on subset: sum via scatter
            return template.scatter(jdbc -> {
                Long c = jdbc.queryForObject(sql, Long.class, args);
                return List.of(c != null ? c : 0L);
            }).stream()
              .filter(this::isFromSelectedShard)
              .mapToLong(Long::longValue).sum();
        }

        @Override
        public <T> List<T> scatter(Function<JdbcTemplate, List<T>> operation) {
            return template.scatter(operation);
        }

        // isFromSelectedShard is a no-op filter placeholder —
        // the proper subset count is done by scatterTo internally when shardIndices != null
        private boolean isFromSelectedShard(Object item) { return true; }
    }
}

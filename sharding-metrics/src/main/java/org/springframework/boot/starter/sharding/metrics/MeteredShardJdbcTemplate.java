package org.springframework.boot.starter.sharding.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.jdbc.ShardJdbcTemplate;
import org.springframework.dao.DataAccessException;

import javax.sql.DataSource;

/**
 * Extension of {@link ShardJdbcTemplate} that wraps every shard operation with
 * Micrometer timers and error counters.
 *
 * <p>Emits:
 * <ul>
 *   <li>{@code sharding.query.latency} — timer tagged with {@code shard} and {@code operation}</li>
 *   <li>{@code sharding.errors} — counter tagged with {@code shard} and {@code exception} class</li>
 * </ul>
 */
public class MeteredShardJdbcTemplate extends ShardJdbcTemplate {

    private final ShardRouter shardRouter;
    private final MeterRegistry registry;

    public MeteredShardJdbcTemplate(DataSource routingDataSource, ShardRouter shardRouter,
                                    MeterRegistry registry) {
        super(routingDataSource);
        this.shardRouter = shardRouter;
        this.registry = registry;
    }

    @Override
    protected <T> T executeWithShardKey(long shardKey, String operation, ShardOperation<T> op) {
        String shardName = resolveShardName(shardKey);
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = super.executeWithShardKey(shardKey, operation, op);
            sample.stop(Timer.builder(ShardMetricsConstants.QUERY_LATENCY)
                .tag(ShardMetricsConstants.TAG_SHARD, shardName)
                .tag(ShardMetricsConstants.TAG_OPERATION, operation)
                .register(registry));
            return result;
        } catch (Exception e) {
            sample.stop(Timer.builder(ShardMetricsConstants.QUERY_LATENCY)
                .tag(ShardMetricsConstants.TAG_SHARD, shardName)
                .tag(ShardMetricsConstants.TAG_OPERATION, operation)
                .register(registry));
            Counter.builder(ShardMetricsConstants.ERROR_COUNT)
                .tag(ShardMetricsConstants.TAG_SHARD, shardName)
                .tag(ShardMetricsConstants.TAG_EXCEPTION, e.getClass().getSimpleName())
                .register(registry)
                .increment();
            if (e instanceof DataAccessException dae) throw dae;
            throw new DataAccessException("Shard operation failed for key: " + shardKey, e) {};
        }
    }

    private String resolveShardName(long shardKey) {
        try {
            return shardRouter.resolve(shardKey).name();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

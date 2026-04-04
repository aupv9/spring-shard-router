package org.springframework.boot.starter.sharding.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.starter.sharding.core.ShardRouter;

/**
 * {@link MeterBinder} that registers live gauges reflecting the current state of the
 * shard router.
 *
 * <p>Gauges:
 * <ul>
 *   <li>{@code sharding.shard.count} — number of configured shards</li>
 * </ul>
 */
public class ShardActuatorMetrics implements MeterBinder {

    private final ShardRouter shardRouter;

    public ShardActuatorMetrics(ShardRouter shardRouter) {
        this.shardRouter = shardRouter;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(ShardMetricsConstants.SHARD_COUNT, shardRouter, ShardRouter::getShardCount)
            .description("Number of configured shards")
            .register(registry);
    }
}

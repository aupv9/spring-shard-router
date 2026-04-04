package org.springframework.boot.starter.sharding.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardRouter;

/**
 * Decorator for {@link ShardRouter} that emits Micrometer metrics on every routing decision.
 *
 * <p>Emits:
 * <ul>
 *   <li>{@code sharding.routing.count} — counter per resolved shard name</li>
 *   <li>{@code sharding.routing.latency} — timer for the resolution call</li>
 * </ul>
 */
public class MeteredShardRouter implements ShardRouter {

    private final ShardRouter delegate;
    private final MeterRegistry registry;
    private final String strategyName;

    public MeteredShardRouter(ShardRouter delegate, MeterRegistry registry, String strategyName) {
        this.delegate = delegate;
        this.registry = registry;
        this.strategyName = strategyName;
    }

    @Override
    public Shard resolve(long shardKey) {
        Timer.Sample sample = Timer.start(registry);
        Shard shard = delegate.resolve(shardKey);
        sample.stop(Timer.builder(ShardMetricsConstants.ROUTING_LATENCY)
            .tag(ShardMetricsConstants.TAG_SHARD, shard.name())
            .register(registry));

        Counter.builder(ShardMetricsConstants.ROUTING_COUNT)
            .tag(ShardMetricsConstants.TAG_SHARD, shard.name())
            .tag(ShardMetricsConstants.TAG_STRATEGY, strategyName)
            .register(registry)
            .increment();

        return shard;
    }

    @Override
    public int getShardCount() {
        return delegate.getShardCount();
    }

    @Override
    public Shard getShard(int index) {
        return delegate.getShard(index);
    }
}

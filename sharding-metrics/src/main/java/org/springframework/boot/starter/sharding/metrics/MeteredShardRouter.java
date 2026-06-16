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
 *   <li>{@code sharding.routing.count} — counter per resolved shard name and strategy</li>
 *   <li>{@code sharding.routing.latency} — timer for the resolution call, tagged by shard</li>
 * </ul>
 *
 * <p>All {@link ShardRouter} interface methods are delegated to the wrapped router, including
 * the optional {@link #addShard} and {@link #removeShard} methods so that dynamic shard
 * management works correctly when metrics are active.
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

    /**
     * Delegates to the underlying router. Dynamic shard addition is only supported
     * when the delegate is a {@link org.springframework.boot.starter.sharding.core.ConsistentHashShardRouter}.
     */
    @Override
    public void addShard(Shard shard) {
        delegate.addShard(shard);
    }

    /**
     * Delegates to the underlying router. Dynamic shard removal is only supported
     * when the delegate is a {@link org.springframework.boot.starter.sharding.core.ConsistentHashShardRouter}.
     */
    @Override
    public void removeShard(int shardIndex) {
        delegate.removeShard(shardIndex);
    }

    /** Returns the raw (un-metered) router for use in health checks and gauges. */
    public ShardRouter getDelegate() {
        return delegate;
    }
}

package org.springframework.boot.starter.sharding.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardRouter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MeteredShardRouter}.
 */
class MeteredShardRouterTest {

    private MeterRegistry registry;
    private ShardRouter delegate;
    private MeteredShardRouter meteredRouter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        delegate = mock(ShardRouter.class);
        meteredRouter = new MeteredShardRouter(delegate, registry, "hash");
    }

    @Test
    void resolve_incrementsRoutingCounter() {
        Shard shard = mock(Shard.class);
        when(shard.name()).thenReturn("shard-0");
        when(delegate.resolve(100L)).thenReturn(shard);

        meteredRouter.resolve(100L);

        Counter counter = registry.find(ShardMetricsConstants.ROUTING_COUNT)
            .tag(ShardMetricsConstants.TAG_SHARD, "shard-0")
            .tag(ShardMetricsConstants.TAG_STRATEGY, "hash")
            .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void resolve_recordsRoutingLatencyTimer() {
        Shard shard = mock(Shard.class);
        when(shard.name()).thenReturn("shard-1");
        when(delegate.resolve(200L)).thenReturn(shard);

        meteredRouter.resolve(200L);

        var timer = registry.find(ShardMetricsConstants.ROUTING_LATENCY)
            .tag(ShardMetricsConstants.TAG_SHARD, "shard-1")
            .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    void resolve_multipleInvocations_counterAccumulates() {
        Shard shard = mock(Shard.class);
        when(shard.name()).thenReturn("shard-0");
        when(delegate.resolve(anyLong())).thenReturn(shard);

        meteredRouter.resolve(1L);
        meteredRouter.resolve(2L);
        meteredRouter.resolve(3L);

        Counter counter = registry.find(ShardMetricsConstants.ROUTING_COUNT)
            .tag(ShardMetricsConstants.TAG_SHARD, "shard-0")
            .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    void getShardCount_delegatesToUnderlying() {
        when(delegate.getShardCount()).thenReturn(4);
        assertThat(meteredRouter.getShardCount()).isEqualTo(4);
    }

    @Test
    void getShard_delegatesToUnderlying() {
        Shard shard = mock(Shard.class);
        when(delegate.getShard(2)).thenReturn(shard);
        assertThat(meteredRouter.getShard(2)).isSameAs(shard);
    }
}

package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * End-to-end routing of non-numeric shard keys (Gap #1) through {@link ShardRouter}
 * and {@link ShardContext}.
 */
class GenericShardKeyRoutingTest {

    private ShardRouter newRouter(int shardCount) {
        List<Shard> shards = java.util.stream.IntStream.range(0, shardCount)
            .mapToObj(i -> Shard.of("shard-" + i, i, mock(javax.sql.DataSource.class)))
            .toList();
        return new HashShardRouter(shards);
    }

    @AfterEach
    void cleanup() {
        ShardContext.clear();
    }

    @Test
    void resolveObjectMatchesResolveOfConvertedLong() {
        ShardRouter router = newRouter(4);
        String tenant = "tenant-xyz";
        long converted = ShardKeyConverter.DEFAULT.toLong(tenant);

        assertThat(router.resolve(tenant).index())
            .isEqualTo(router.resolve(converted).index());
    }

    @Test
    void resolveObjectIsStableForSameKey() {
        ShardRouter router = newRouter(8);
        UUID id = UUID.randomUUID();
        assertThat(router.resolve(id).index()).isEqualTo(router.resolve(id).index());
    }

    @Test
    void setKeyStoresConvertedLongInContext() {
        ShardContext.setKey("tenant-abc");
        assertThat(ShardContext.get())
            .isEqualTo(ShardKeyConverter.DEFAULT.toLong("tenant-abc"));
    }

    @Test
    void setKeyRoutesConsistentlyWithResolveObject() {
        ShardRouter router = newRouter(4);
        ShardContext.setKey("tenant-abc");
        Shard viaContext = router.resolve(ShardContext.get());
        Shard viaObject = router.resolve("tenant-abc");
        assertThat(viaContext.index()).isEqualTo(viaObject.index());
    }
}

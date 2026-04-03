package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ConsistentHashShardRouterTest {

    private List<Shard> shards;
    private ConsistentHashShardRouter router;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        shards = Arrays.asList(
            Shard.of("shard-0", 0, mock(DataSource.class)),
            Shard.of("shard-1", 1, mock(DataSource.class)),
            Shard.of("shard-2", 2, mock(DataSource.class))
        );
        router = new ConsistentHashShardRouter(shards, 150);
    }

    @Test
    void shouldResolveShardConsistently() {
        long key = 12345L;
        Shard first = router.resolve(key);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, router.resolve(key), "Same key must always resolve to same shard");
        }
    }

    @Test
    void shouldReturnCorrectShardCount() {
        assertEquals(3, router.getShardCount());
    }

    @Test
    void shouldGetShardByIndex() {
        assertEquals(shards.get(0), router.getShard(0));
        assertEquals(shards.get(1), router.getShard(1));
        assertEquals(shards.get(2), router.getShard(2));
    }

    @Test
    void shouldThrowForInvalidShardIndex() {
        assertThrows(IllegalArgumentException.class, () -> router.getShard(-1));
        assertThrows(IllegalArgumentException.class, () -> router.getShard(3));
    }

    @Test
    void shouldRouteToValidShardIndex() {
        for (long key = 0; key < 500; key++) {
            Shard shard = router.resolve(key);
            assertTrue(shard.index() >= 0 && shard.index() < 3);
            assertTrue(shards.contains(shard));
        }
    }

    @Test
    void shouldHandleOverridesAtConstruction() {
        Map<Long, Integer> overrides = new HashMap<>();
        overrides.put(1001L, 0);
        overrides.put(1002L, 2);
        ConsistentHashShardRouter routerWithOverrides = new ConsistentHashShardRouter(shards, overrides, 150);

        assertEquals(shards.get(0), routerWithOverrides.resolve(1001L));
        assertEquals(shards.get(2), routerWithOverrides.resolve(1002L));
    }

    @Test
    void shouldAddAndRemoveOverrides() {
        long key = 7777L;
        Shard original = router.resolve(key);

        int target = (original.index() + 1) % 3;
        router.addOverride(key, target);
        assertEquals(shards.get(target), router.resolve(key), "Override should take effect");

        router.removeOverride(key);
        assertEquals(original, router.resolve(key), "After removal should return to original routing");
    }

    @Test
    void shouldThrowForInvalidOverrideIndex() {
        assertThrows(IllegalArgumentException.class, () -> router.addOverride(123L, -1));
        assertThrows(IllegalArgumentException.class, () -> router.addOverride(123L, 3));
    }

    @Test
    void shouldThrowForNullOrEmptyShards() {
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashShardRouter(null, 150));
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashShardRouter(List.of(), 150));
    }

    @Test
    void shouldOverrideTakesPriorityOverRing() {
        // Pick a key and route it normally
        long key = 42L;
        int normalShard = router.resolve(key).index();

        // Force it to a different shard via override
        int forcedShard = (normalShard + 1) % 3;
        router.addOverride(key, forcedShard);

        assertEquals(forcedShard, router.resolve(key).index(),
            "Override must take priority over consistent hash ring");
    }
}

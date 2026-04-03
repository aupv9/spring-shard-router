package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashShardStrategyTest {

    @Test
    void shouldRouteToValidShardIndex() {
        ConsistentHashShardStrategy strategy = new ConsistentHashShardStrategy(3, 150);
        for (long key = 0; key < 1000; key++) {
            int index = strategy.shardIndex(key);
            assertTrue(index >= 0 && index < 3,
                "Expected index in [0,3), got " + index + " for key " + key);
        }
    }

    @Test
    void shouldBeConsistent() {
        ConsistentHashShardStrategy strategy = new ConsistentHashShardStrategy(4, 150);
        long key = 99999L;
        int first = strategy.shardIndex(key);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, strategy.shardIndex(key), "Same key must always map to same shard");
        }
    }

    @Test
    void shouldDistributeEvenlyWithManyKeys() {
        int shardCount = 3;
        ConsistentHashShardStrategy strategy = new ConsistentHashShardStrategy(shardCount, 150);
        int[] counts = new int[shardCount];

        for (long key = 1; key <= 3000; key++) {
            counts[strategy.shardIndex(key)]++;
        }

        // Each shard should receive between 20% and 47% of keys (wide tolerance for hash variance)
        int total = 3000;
        for (int i = 0; i < shardCount; i++) {
            double fraction = (double) counts[i] / total;
            assertTrue(fraction > 0.20 && fraction < 0.47,
                "Shard " + i + " has " + counts[i] + " keys (" + (fraction * 100) + "%), expected ~33%");
        }
    }

    @Test
    void shouldHandleNegativeAndEdgeCaseKeys() {
        ConsistentHashShardStrategy strategy = new ConsistentHashShardStrategy(3, 150);
        assertDoesNotThrow(() -> strategy.shardIndex(0L));
        assertDoesNotThrow(() -> strategy.shardIndex(-1L));
        assertDoesNotThrow(() -> strategy.shardIndex(Long.MIN_VALUE));
        assertDoesNotThrow(() -> strategy.shardIndex(Long.MAX_VALUE));
    }

    @Test
    void shouldAddShardAndRouteToIt() {
        // Start with 2 shards
        ConsistentHashShardStrategy strategy = new ConsistentHashShardStrategy(2, 150);

        // Snapshot which shard each key goes to
        int[] before = new int[1000];
        for (int key = 0; key < 1000; key++) {
            before[key] = strategy.shardIndex(key);
        }

        // Add shard index 2
        strategy.addShard(2);

        // After adding, shard 2 should receive some keys
        int remappedToNew = 0;
        for (int key = 0; key < 1000; key++) {
            int after = strategy.shardIndex(key);
            assertTrue(after >= 0 && after <= 2, "Index must be in [0,2]");
            if (after == 2) remappedToNew++;
            // Keys that stay on their original shard must not move to a different existing shard
            if (after != 2 && before[key] != after) {
                fail("Key " + key + " moved from shard " + before[key] + " to " + after + " without going through new shard");
            }
        }
        assertTrue(remappedToNew > 0, "Some keys should have migrated to the new shard");
    }

    @Test
    void shouldRemoveShardAndRedistribute() {
        ConsistentHashShardStrategy strategy = new ConsistentHashShardStrategy(3, 150);
        strategy.removeShard(1);

        // After removal, no key should route to shard 1
        for (long key = 0; key < 1000; key++) {
            int index = strategy.shardIndex(key);
            assertNotEquals(1, index, "Removed shard should not receive traffic, key=" + key);
            assertTrue(index == 0 || index == 2, "Must route to remaining shards");
        }
    }

    @Test
    void shouldThrowForInvalidConstructorArgs() {
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashShardStrategy(0, 150));
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashShardStrategy(-1, 150));
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashShardStrategy(3, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashShardStrategy(3, -1));
    }

    @Test
    void shouldThrowWhenRingIsEmpty() {
        ConsistentHashShardStrategy strategy = new ConsistentHashShardStrategy(1, 5);
        strategy.removeShard(0);
        assertThrows(IllegalStateException.class, () -> strategy.shardIndex(42L));
    }
}

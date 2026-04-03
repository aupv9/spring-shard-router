package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HashShardStrategy
 */
class HashShardStrategyTest {
    
    private final HashShardStrategy strategy = new HashShardStrategy();
    
    @Test
    void shouldDistributeKeysEvenly() {
        int totalShards = 3;
        int[] shardCounts = new int[totalShards];
        
        // Test 1000 keys
        for (long key = 1; key <= 1000; key++) {
            int shardIndex = strategy.shardIndex(key, totalShards);
            assertTrue(shardIndex >= 0 && shardIndex < totalShards);
            shardCounts[shardIndex]++;
        }
        
        // Each shard should have roughly 1/3 of the keys (within 20% tolerance)
        int expectedPerShard = 1000 / totalShards;
        for (int count : shardCounts) {
            assertTrue(count > expectedPerShard * 0.8);
            assertTrue(count < expectedPerShard * 1.2);
        }
    }
    
    @Test
    void shouldBeConsistent() {
        int totalShards = 5;
        long testKey = 12345L;
        
        int firstResult = strategy.shardIndex(testKey, totalShards);
        
        // Same key should always map to same shard
        for (int i = 0; i < 100; i++) {
            assertEquals(firstResult, strategy.shardIndex(testKey, totalShards));
        }
    }
    
    @Test
    void shouldHandleEdgeCases() {
        // Test with key 0
        assertDoesNotThrow(() -> strategy.shardIndex(0L, 3));
        
        // Test with negative key
        assertDoesNotThrow(() -> strategy.shardIndex(-123L, 3));
        
        // Test with max long
        assertDoesNotThrow(() -> strategy.shardIndex(Long.MAX_VALUE, 3));
        
        // Test with min long
        assertDoesNotThrow(() -> strategy.shardIndex(Long.MIN_VALUE, 3));
    }
    
    @Test
    void shouldThrowForInvalidShardCount() {
        assertThrows(IllegalArgumentException.class, () -> 
            strategy.shardIndex(123L, 0));
        
        assertThrows(IllegalArgumentException.class, () -> 
            strategy.shardIndex(123L, -1));
    }
}
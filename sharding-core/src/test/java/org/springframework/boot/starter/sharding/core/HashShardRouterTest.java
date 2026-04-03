package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for HashShardRouter
 */
class HashShardRouterTest {
    
    @Mock
    private DataSource dataSource1;
    
    @Mock
    private DataSource dataSource2;
    
    @Mock
    private DataSource dataSource3;
    
    private List<Shard> shards;
    private HashShardRouter router;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        shards = Arrays.asList(
            Shard.of("shard-0", 0, dataSource1),
            Shard.of("shard-1", 1, dataSource2),
            Shard.of("shard-2", 2, dataSource3)
        );
        
        router = new HashShardRouter(shards);
    }
    
    @Test
    void shouldResolveShardConsistently() {
        long testKey = 12345L;
        
        Shard firstResult = router.resolve(testKey);
        
        // Same key should always resolve to same shard
        for (int i = 0; i < 10; i++) {
            assertEquals(firstResult, router.resolve(testKey));
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
    void shouldHandleOverrides() {
        Map<Long, Integer> overrides = new HashMap<>();
        overrides.put(1001L, 0); // Force key 1001 to shard 0
        overrides.put(1002L, 2); // Force key 1002 to shard 2
        
        HashShardRouter routerWithOverrides = new HashShardRouter(shards, overrides);
        
        // Override keys should go to specified shards
        assertEquals(shards.get(0), routerWithOverrides.resolve(1001L));
        assertEquals(shards.get(2), routerWithOverrides.resolve(1002L));
        
        // Non-override keys should use normal routing
        Shard normalShard = routerWithOverrides.resolve(9999L);
        assertTrue(shards.contains(normalShard));
    }
    
    @Test
    void shouldAddAndRemoveOverrides() {
        long testKey = 5555L;
        
        // Normal routing first
        Shard originalShard = router.resolve(testKey);
        
        // Add override to different shard
        int targetShardIndex = (originalShard.index() + 1) % 3;
        router.addOverride(testKey, targetShardIndex);
        
        // Should now route to override shard
        assertEquals(shards.get(targetShardIndex), router.resolve(testKey));
        
        // Remove override
        router.removeOverride(testKey);
        
        // Should go back to original routing
        assertEquals(originalShard, router.resolve(testKey));
    }
    
    @Test
    void shouldThrowForInvalidOverrideIndex() {
        assertThrows(IllegalArgumentException.class, () -> 
            router.addOverride(123L, -1));
        
        assertThrows(IllegalArgumentException.class, () -> 
            router.addOverride(123L, 3));
    }
    
    @Test
    void shouldThrowForEmptyShards() {
        assertThrows(IllegalArgumentException.class, () -> 
            new HashShardRouter(Arrays.asList()));
        
        assertThrows(IllegalArgumentException.class, () -> 
            new HashShardRouter(null));
    }
}
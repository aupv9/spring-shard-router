package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ShardContext
 */
class ShardContextTest {
    
    @AfterEach
    void cleanup() {
        ShardContext.clear();
    }
    
    @Test
    void shouldSetAndGetShardKey() {
        long testKey = 12345L;
        
        assertNull(ShardContext.get());
        assertFalse(ShardContext.isSet());
        
        ShardContext.set(testKey);
        
        assertEquals(testKey, ShardContext.get());
        assertTrue(ShardContext.isSet());
    }
    
    @Test
    void shouldClearShardKey() {
        long testKey = 12345L;
        
        ShardContext.set(testKey);
        assertTrue(ShardContext.isSet());
        
        ShardContext.clear();
        
        assertNull(ShardContext.get());
        assertFalse(ShardContext.isSet());
    }
    
    @Test
    void shouldExecuteWithShardKey() {
        long testKey = 12345L;
        boolean[] executed = {false};
        
        assertNull(ShardContext.get());
        
        ShardContext.execute(testKey, () -> {
            assertEquals(testKey, ShardContext.get());
            executed[0] = true;
        });
        
        assertTrue(executed[0]);
        assertNull(ShardContext.get()); // Should be cleared after execution
    }
    
    @Test
    void shouldClearContextEvenOnException() {
        long testKey = 12345L;
        
        assertThrows(RuntimeException.class, () -> {
            ShardContext.execute(testKey, () -> {
                assertEquals(testKey, ShardContext.get());
                throw new RuntimeException("Test exception");
            });
        });
        
        assertNull(ShardContext.get()); // Should be cleared even after exception
    }
    
    @Test
    void shouldBeThreadLocal() throws ExecutionException, InterruptedException {
        long mainThreadKey = 111L;
        long otherThreadKey = 222L;
        
        // Set key in main thread
        ShardContext.set(mainThreadKey);
        assertEquals(mainThreadKey, ShardContext.get());
        
        // Execute in different thread
        CompletableFuture<Long> future = CompletableFuture.supplyAsync(() -> {
            // Should not see main thread's key
            assertNull(ShardContext.get());
            
            // Set different key in this thread
            ShardContext.set(otherThreadKey);
            return ShardContext.get();
        });
        
        // Other thread should have its own key
        assertEquals(otherThreadKey, future.get());
        
        // Main thread should still have its key
        assertEquals(mainThreadKey, ShardContext.get());
    }
    
    @Test
    void shouldHandleMultipleSetCalls() {
        ShardContext.set(111L);
        assertEquals(111L, ShardContext.get());
        
        ShardContext.set(222L);
        assertEquals(222L, ShardContext.get());
        
        ShardContext.set(333L);
        assertEquals(333L, ShardContext.get());
    }
}
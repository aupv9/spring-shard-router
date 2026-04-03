package org.springframework.boot.starter.sharding.core;

/**
 * Thread-local context for shard key management
 * Ensures shard key is available throughout the request lifecycle
 */
public class ShardContext {
    
    private static final ThreadLocal<Long> SHARD_KEY = new ThreadLocal<>();
    
    /**
     * Set shard key for current thread
     * @param key the shard key
     */
    public static void set(long key) {
        SHARD_KEY.set(key);
    }
    
    /**
     * Get shard key for current thread
     * @return shard key or null if not set
     */
    public static Long get() {
        return SHARD_KEY.get();
    }
    
    /**
     * Clear shard key for current thread
     * Should be called in finally block to prevent memory leaks
     */
    public static void clear() {
        SHARD_KEY.remove();
    }
    
    /**
     * Execute code block with shard key context
     * Automatically clears context after execution
     * @param shardKey the shard key
     * @param runnable code to execute
     */
    public static void execute(long shardKey, Runnable runnable) {
        try {
            set(shardKey);
            runnable.run();
        } finally {
            clear();
        }
    }
    
    /**
     * Check if shard key is set for current thread
     * @return true if shard key is set
     */
    public static boolean isSet() {
        return SHARD_KEY.get() != null;
    }
}
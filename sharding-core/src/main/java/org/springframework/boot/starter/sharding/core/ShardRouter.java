package org.springframework.boot.starter.sharding.core;

/**
 * Core interface for shard routing logic
 * Resolves shard key to specific shard instance
 */
public interface ShardRouter {
    
    /**
     * Resolve shard key to target shard
     * @param shardKey the key to route
     * @return target shard
     * @throws IllegalArgumentException if shard key is invalid
     */
    Shard resolve(long shardKey);
    
    /**
     * Get total number of shards
     * @return shard count
     */
    int getShardCount();
    
    /**
     * Get shard by index
     * @param index shard index
     * @return shard instance
     */
    Shard getShard(int index);
}
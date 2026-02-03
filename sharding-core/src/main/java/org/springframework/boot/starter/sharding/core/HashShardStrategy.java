package org.springframework.boot.starter.sharding.core;

import com.google.common.hash.Hashing;

/**
 * Hash-based sharding strategy using Murmur3 hash
 * Provides consistent and uniform distribution
 */
public class HashShardStrategy {
    
    /**
     * Calculate shard index using consistent hash
     * @param key the shard key
     * @param totalShards total number of shards
     * @return shard index (0-based)
     */
    public int shardIndex(long key, int totalShards) {
        if (totalShards <= 0) {
            throw new IllegalArgumentException("Total shards must be positive");
        }
        
        // Use Murmur3 for consistent hashing
        int hash = Hashing.murmur3_32_fixed()
            .hashLong(key)
            .asInt();
            
        // Ensure positive result and distribute evenly
        return Math.abs(hash % totalShards);
    }
}
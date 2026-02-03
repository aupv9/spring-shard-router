package org.springframework.boot.starter.sharding.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hash-based shard router with override support
 * Supports VIP routing and migration scenarios
 */
public class HashShardRouter implements ShardRouter {
    
    private final List<Shard> shards;
    private final HashShardStrategy strategy;
    private final Map<Long, Integer> overrides;
    
    public HashShardRouter(List<Shard> shards) {
        this(shards, new ConcurrentHashMap<>());
    }
    
    public HashShardRouter(List<Shard> shards, Map<Long, Integer> overrides) {
        if (shards == null || shards.isEmpty()) {
            throw new IllegalArgumentException("Shards cannot be null or empty");
        }
        this.shards = List.copyOf(shards);
        this.strategy = new HashShardStrategy();
        this.overrides = new ConcurrentHashMap<>(overrides);
    }
    
    @Override
    public Shard resolve(long shardKey) {
        // Check for explicit override first (VIP/migration)
        Integer overrideIndex = overrides.get(shardKey);
        if (overrideIndex != null) {
            return getShard(overrideIndex);
        }
        
        // Use hash strategy for normal routing
        int shardIndex = strategy.shardIndex(shardKey, shards.size());
        return shards.get(shardIndex);
    }
    
    @Override
    public int getShardCount() {
        return shards.size();
    }
    
    @Override
    public Shard getShard(int index) {
        if (index < 0 || index >= shards.size()) {
            throw new IllegalArgumentException("Invalid shard index: " + index);
        }
        return shards.get(index);
    }
    
    /**
     * Add shard key override for VIP or migration
     * @param shardKey the key to override
     * @param targetShardIndex target shard index
     */
    public void addOverride(long shardKey, int targetShardIndex) {
        if (targetShardIndex < 0 || targetShardIndex >= shards.size()) {
            throw new IllegalArgumentException("Invalid target shard index: " + targetShardIndex);
        }
        overrides.put(shardKey, targetShardIndex);
    }
    
    /**
     * Remove shard key override
     * @param shardKey the key to remove override for
     */
    public void removeOverride(long shardKey) {
        overrides.remove(shardKey);
    }
}
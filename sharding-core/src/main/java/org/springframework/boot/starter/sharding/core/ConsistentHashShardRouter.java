package org.springframework.boot.starter.sharding.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shard router using consistent hashing.
 *
 * <p>Use this instead of {@link HashShardRouter} when you plan to add or remove shards
 * in production. Consistent hashing minimises the number of keys that need to be
 * migrated when the cluster size changes (~1/N keys vs. all keys for modulo hashing).
 *
 * <p>Override map works exactly as in {@link HashShardRouter}: explicit entries take
 * priority over the ring lookup. This allows pinning VIP accounts or migrating
 * individual keys independently of the hash ring.
 */
public class ConsistentHashShardRouter implements ShardRouter {

    private final List<Shard> shards;
    private final ConsistentHashShardStrategy strategy;
    private final Map<Long, Integer> overrides;

    public ConsistentHashShardRouter(List<Shard> shards, int virtualNodesPerShard) {
        this(shards, new ConcurrentHashMap<>(), virtualNodesPerShard);
    }

    public ConsistentHashShardRouter(List<Shard> shards, Map<Long, Integer> overrides, int virtualNodesPerShard) {
        if (shards == null || shards.isEmpty()) {
            throw new IllegalArgumentException("Shards cannot be null or empty");
        }
        this.shards = List.copyOf(shards);
        this.strategy = new ConsistentHashShardStrategy(shards.size(), virtualNodesPerShard);
        this.overrides = new ConcurrentHashMap<>(overrides);
    }

    @Override
    public Shard resolve(long shardKey) {
        Integer overrideIndex = overrides.get(shardKey);
        if (overrideIndex != null) {
            return getShard(overrideIndex);
        }
        return shards.get(strategy.shardIndex(shardKey));
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

    public void addOverride(long shardKey, int targetShardIndex) {
        if (targetShardIndex < 0 || targetShardIndex >= shards.size()) {
            throw new IllegalArgumentException("Invalid target shard index: " + targetShardIndex);
        }
        overrides.put(shardKey, targetShardIndex);
    }

    public void removeOverride(long shardKey) {
        overrides.remove(shardKey);
    }
}

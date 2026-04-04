package org.springframework.boot.starter.sharding.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 *
 * <p>This implementation supports runtime shard addition and removal via
 * {@link #addShard(Shard)} and {@link #removeShard(int)}. Reads use a shared lock
 * while mutations take an exclusive lock, so normal routing is non-blocking.
 */
public class ConsistentHashShardRouter implements ShardRouter {

    private final AtomicReference<List<Shard>> shards;
    private ConsistentHashShardStrategy strategy;
    private final Map<Long, Integer> overrides;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final int virtualNodesPerShard;

    public ConsistentHashShardRouter(List<Shard> shards, int virtualNodesPerShard) {
        this(shards, new ConcurrentHashMap<>(), virtualNodesPerShard);
    }

    public ConsistentHashShardRouter(List<Shard> shards, Map<Long, Integer> overrides,
                                     int virtualNodesPerShard) {
        if (shards == null || shards.isEmpty()) {
            throw new IllegalArgumentException("Shards cannot be null or empty");
        }
        this.shards = new AtomicReference<>(List.copyOf(shards));
        this.strategy = new ConsistentHashShardStrategy(shards.size(), virtualNodesPerShard);
        this.overrides = new ConcurrentHashMap<>(overrides);
        this.virtualNodesPerShard = virtualNodesPerShard;
    }

    @Override
    public Shard resolve(long shardKey) {
        lock.readLock().lock();
        try {
            Integer overrideIndex = overrides.get(shardKey);
            if (overrideIndex != null) {
                return getShard(overrideIndex);
            }
            return shards.get().get(strategy.shardIndex(shardKey));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int getShardCount() {
        return shards.get().size();
    }

    @Override
    public Shard getShard(int index) {
        List<Shard> current = shards.get();
        if (index < 0 || index >= current.size()) {
            throw new IllegalArgumentException("Invalid shard index: " + index);
        }
        return current.get(index);
    }

    @Override
    public void addShard(Shard shard) {
        lock.writeLock().lock();
        try {
            List<Shard> current = new ArrayList<>(shards.get());
            int newIndex = current.size(); // new shard receives the next sequential index
            current.add(shard);
            shards.set(List.copyOf(current));
            // Incrementally add only the new shard's vnodes to the existing ring
            strategy.addShard(newIndex);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void removeShard(int shardIndex) {
        lock.writeLock().lock();
        try {
            List<Shard> current = new ArrayList<>(shards.get());
            if (shardIndex < 0 || shardIndex >= current.size()) {
                throw new IllegalArgumentException("Invalid shard index: " + shardIndex);
            }
            // Remove vnodes from ring before updating the shard list
            strategy.removeShard(shardIndex);
            current.remove(shardIndex);
            shards.set(List.copyOf(current));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addOverride(long shardKey, int targetShardIndex) {
        lock.readLock().lock();
        try {
            if (targetShardIndex < 0 || targetShardIndex >= shards.get().size()) {
                throw new IllegalArgumentException("Invalid target shard index: " + targetShardIndex);
            }
        } finally {
            lock.readLock().unlock();
        }
        overrides.put(shardKey, targetShardIndex);
    }

    public void removeOverride(long shardKey) {
        overrides.remove(shardKey);
    }
}

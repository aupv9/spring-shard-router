package org.springframework.boot.starter.sharding.core;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Consistent hashing strategy using a virtual node ring.
 *
 * <p>Unlike modulo hashing ({@link HashShardStrategy}), when you add a new shard
 * only ~1/N of keys are remapped instead of all of them. This makes it safe
 * to grow the cluster without a full data migration.
 *
 * <p>Virtual nodes (vnodes) ensure uniform distribution even with a small number
 * of physical shards. The default of 150 vnodes per shard gives < 5% variance
 * in load distribution.
 */
public class ConsistentHashShardStrategy {

    private final NavigableMap<Long, Integer> ring = new TreeMap<>();
    private final int virtualNodesPerShard;

    public ConsistentHashShardStrategy(int totalShards, int virtualNodesPerShard) {
        if (totalShards <= 0) throw new IllegalArgumentException("totalShards must be positive");
        if (virtualNodesPerShard <= 0) throw new IllegalArgumentException("virtualNodesPerShard must be positive");
        this.virtualNodesPerShard = virtualNodesPerShard;
        for (int i = 0; i < totalShards; i++) {
            addShardToRing(i);
        }
    }

    /**
     * Resolve a shard key to a physical shard index.
     * Walks clockwise on the ring to find the next virtual node.
     */
    public int shardIndex(long key) {
        if (ring.isEmpty()) throw new IllegalStateException("Consistent hash ring is empty");
        long hash = hashKey(key);
        // ceilingEntry: first entry >= hash; wrap around to first entry if none found
        NavigableMap.Entry<Long, Integer> entry = ring.ceilingEntry(hash);
        if (entry == null) entry = ring.firstEntry();
        return entry.getValue();
    }

    /**
     * Add a new shard to the ring at runtime (e.g. during scale-out).
     * After calling this, only the keys that map to virtual nodes of the new shard
     * will be remapped; all others remain on their existing shards.
     */
    public void addShard(int shardIndex) {
        addShardToRing(shardIndex);
    }

    /**
     * Remove a shard from the ring (e.g. during decommission).
     * Keys that were on this shard will be redistributed to adjacent nodes on the ring.
     */
    public void removeShard(int shardIndex) {
        for (int v = 0; v < virtualNodesPerShard; v++) {
            long hash = hashVNode(shardIndex, v);
            ring.remove(hash);
        }
    }

    private void addShardToRing(int shardIndex) {
        for (int v = 0; v < virtualNodesPerShard; v++) {
            long hash = hashVNode(shardIndex, v);
            ring.put(hash, shardIndex);
        }
    }

    private long hashVNode(int shardIndex, int vnodeIndex) {
        String vnodeId = "shard-" + shardIndex + "-vnode-" + vnodeIndex;
        return Hashing.murmur3_128()
            .hashString(vnodeId, StandardCharsets.UTF_8)
            .asLong();
    }

    private long hashKey(long key) {
        return Hashing.murmur3_128()
            .hashLong(key)
            .asLong();
    }
}

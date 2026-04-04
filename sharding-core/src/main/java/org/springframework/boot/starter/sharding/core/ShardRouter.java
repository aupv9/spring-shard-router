package org.springframework.boot.starter.sharding.core;

/**
 * Core interface for shard routing logic.
 * Resolves shard key to specific shard instance.
 *
 * <p>Optional dynamic-management methods ({@link #addShard} and {@link #removeShard})
 * default to throwing {@link UnsupportedOperationException}. Implementations that
 * support runtime shard addition/removal (e.g., {@link ConsistentHashShardRouter})
 * override these methods.
 */
public interface ShardRouter {

    /**
     * Resolve shard key to target shard.
     * @param shardKey the key to route
     * @return target shard
     * @throws IllegalArgumentException if shard key is invalid
     */
    Shard resolve(long shardKey);

    /**
     * Get total number of shards.
     * @return shard count
     */
    int getShardCount();

    /**
     * Get shard by index.
     * @param index shard index
     * @return shard instance
     */
    Shard getShard(int index);

    /**
     * Add a new shard to the router at runtime.
     * Only supported by routers that implement consistent hashing.
     *
     * @param shard the shard to add (must include a configured DataSource)
     * @throws UnsupportedOperationException if this router does not support dynamic management
     */
    default void addShard(Shard shard) {
        throw new UnsupportedOperationException(
            getClass().getSimpleName() + " does not support dynamic shard addition");
    }

    /**
     * Remove a shard by index from the router at runtime.
     * Only supported by routers that implement consistent hashing.
     *
     * @param shardIndex zero-based index of the shard to remove
     * @throws UnsupportedOperationException if this router does not support dynamic management
     */
    default void removeShard(int shardIndex) {
        throw new UnsupportedOperationException(
            getClass().getSimpleName() + " does not support dynamic shard removal");
    }
}

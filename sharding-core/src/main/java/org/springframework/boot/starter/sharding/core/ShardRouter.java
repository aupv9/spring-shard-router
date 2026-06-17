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
     * Resolve an arbitrary shard key object (e.g. {@code String} tenant id,
     * {@link java.util.UUID}, composite key) to its target shard.
     *
     * <p>The key is normalised to a {@code long} via {@link ShardKeyConverter#DEFAULT}
     * and then routed through {@link #resolve(long)}. This lets the same router
     * shard by non-numeric keys without changing the underlying routing strategy.
     *
     * @param shardKey the key to route; must not be {@code null}
     * @return target shard
     * @throws IllegalArgumentException if {@code shardKey} is {@code null} or cannot be converted
     */
    default Shard resolve(Object shardKey) {
        return resolve(ShardKeyConverter.DEFAULT.toLong(shardKey));
    }

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

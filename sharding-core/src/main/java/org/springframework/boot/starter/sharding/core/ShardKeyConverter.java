package org.springframework.boot.starter.sharding.core;

/**
 * Converts an arbitrary shard key object into the {@code long} value used by the
 * routing layer (hash ring, modulo strategy, override map, {@link ShardContext}).
 *
 * <p>Out of the box the routing pipeline is built on {@code long} keys
 * ({@link ShardRouter#resolve(long)}, {@code ShardContext.set(long)}). Many real
 * systems, however, shard by values that are not naturally {@code long}:
 * {@code String} tenant ids, {@link java.util.UUID} user ids, composite business
 * keys, and so on. A {@code ShardKeyConverter} normalises those into a stable
 * {@code long} so the existing routing machinery can be reused unchanged.
 *
 * <p>The conversion must be <b>deterministic and stable across JVMs and
 * releases</b>: the same logical key must always map to the same {@code long},
 * otherwise a key could be routed to different shards on different nodes — the
 * exact failure mode this library exists to prevent.
 *
 * @see DefaultShardKeyConverter
 */
@FunctionalInterface
public interface ShardKeyConverter {

    /**
     * Process-wide default converter. Handles {@link Number}, {@link CharSequence},
     * {@link java.util.UUID}, {@code byte[]} and falls back to a stable hash of
     * {@code toString()} for any other type.
     */
    ShardKeyConverter DEFAULT = new DefaultShardKeyConverter();

    /**
     * Convert a shard key object to its {@code long} routing value.
     *
     * @param key the shard key; must not be {@code null}
     * @return the {@code long} value to route with
     * @throws IllegalArgumentException if {@code key} is {@code null} or cannot be converted
     */
    long toLong(Object key);
}

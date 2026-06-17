package org.springframework.boot.starter.sharding.core;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;

/**
 * Helpers for building shard keys, in particular composite keys made of several
 * parts (e.g. {@code tenantId + region}).
 *
 * <p>The produced value is a stable {@code long} suitable for any of the
 * {@code long}-based routing APIs ({@link ShardRouter#resolve(long)},
 * {@code ShardContext.set(long)}). Component order matters: {@code composite(a, b)}
 * and {@code composite(b, a)} generally hash to different shards.
 */
public final class ShardKeys {

    private ShardKeys() {}

    /**
     * Build a stable composite shard key from the given parts.
     *
     * <p>Each non-null part is hashed by its string form with a separator between
     * parts so that {@code composite("a", "bc")} and {@code composite("ab", "c")}
     * do not collide. Null parts are encoded distinctly from the empty string.
     *
     * @param parts the key components in significant order; must not be empty
     * @return a stable {@code long} routing value
     * @throws IllegalArgumentException if {@code parts} is null or empty
     */
    public static long composite(Object... parts) {
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("Composite key requires at least one part");
        }
        Hasher hasher = Hashing.murmur3_128().newHasher();
        for (Object part : parts) {
            if (part == null) {
                hasher.putByte((byte) 0); // distinct marker for null
            } else {
                hasher.putByte((byte) 1)
                      .putString(part.toString(), StandardCharsets.UTF_8);
            }
            hasher.putChar(''); // unit separator between parts
        }
        return hasher.hash().asLong();
    }
}

package org.springframework.boot.starter.sharding.core;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Default {@link ShardKeyConverter} covering the common shard key types.
 *
 * <p>Conversion rules:
 * <ul>
 *   <li>{@link Number} (including {@code Long}, {@code Integer}, {@code BigInteger}…) —
 *       used directly via {@link Number#longValue()}. This keeps numeric keys
 *       identical to the legacy {@code long} routing path.</li>
 *   <li>{@link UUID} — a stable hash of its 128 bits.</li>
 *   <li>{@link CharSequence} ({@code String}, …) — a stable Murmur3 hash of the UTF-8
 *       bytes. Note that the numeric string {@code "123"} does <b>not</b> map to the
 *       number {@code 123L}; strings are always hashed.</li>
 *   <li>{@code byte[]} — a stable Murmur3 hash of the bytes.</li>
 *   <li>anything else — a stable Murmur3 hash of {@link Object#toString()}; relies on
 *       the type having a deterministic {@code toString()}.</li>
 * </ul>
 *
 * <p>All hashing uses Murmur3 (128-bit, lower 64 bits taken) which is fast, well
 * distributed, and stable across JVMs and library versions.
 */
public class DefaultShardKeyConverter implements ShardKeyConverter {

    @Override
    public long toLong(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Shard key must not be null");
        }
        if (key instanceof Number n) {
            return n.longValue();
        }
        if (key instanceof UUID uuid) {
            return Hashing.murmur3_128()
                .newHasher()
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .hash()
                .asLong();
        }
        if (key instanceof byte[] bytes) {
            return Hashing.murmur3_128().hashBytes(bytes).asLong();
        }
        CharSequence text = (key instanceof CharSequence cs) ? cs : key.toString();
        return Hashing.murmur3_128()
            .hashString(text, StandardCharsets.UTF_8)
            .asLong();
    }
}

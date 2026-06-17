package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DefaultShardKeyConverter} (Gap #1: generic shard keys).
 */
class ShardKeyConverterTest {

    private final ShardKeyConverter converter = ShardKeyConverter.DEFAULT;

    @Test
    void numbersConvertToTheirLongValueUnchanged() {
        assertThat(converter.toLong(42L)).isEqualTo(42L);
        assertThat(converter.toLong(42)).isEqualTo(42L);
        assertThat(converter.toLong((short) 42)).isEqualTo(42L);
    }

    @Test
    void conversionIsDeterministicForStrings() {
        long a = converter.toLong("tenant-abc");
        long b = converter.toLong("tenant-abc");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentStringsGenerallyMapToDifferentValues() {
        assertThat(converter.toLong("tenant-a")).isNotEqualTo(converter.toLong("tenant-b"));
    }

    @Test
    void numericStringIsNotEqualToTheNumber() {
        // Strings are always hashed; "123" must not collide with the long 123.
        assertThat(converter.toLong("123")).isNotEqualTo(123L);
    }

    @Test
    void uuidConversionIsDeterministic() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(converter.toLong(id)).isEqualTo(converter.toLong(id));
    }

    @Test
    void byteArrayConversionIsDeterministic() {
        byte[] bytes = {1, 2, 3, 4};
        assertThat(converter.toLong(bytes)).isEqualTo(converter.toLong(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void nullKeyIsRejected() {
        assertThatThrownBy(() -> converter.toLong(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compositeKeyIsDeterministicAndOrderSensitive() {
        assertThat(ShardKeys.composite("tenant-1", "APAC"))
            .isEqualTo(ShardKeys.composite("tenant-1", "APAC"));
        assertThat(ShardKeys.composite("tenant-1", "APAC"))
            .isNotEqualTo(ShardKeys.composite("APAC", "tenant-1"));
    }

    @Test
    void compositeKeyRejectsEmptyParts() {
        assertThatThrownBy(ShardKeys::composite)
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compositeAvoidsTrivialConcatenationCollisions() {
        // ("a","bc") and ("ab","c") would collide under naive concatenation.
        assertThat(ShardKeys.composite("a", "bc"))
            .isNotEqualTo(ShardKeys.composite("ab", "c"));
    }
}

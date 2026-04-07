package org.springframework.boot.starter.sharding.aop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.starter.sharding.jpa.ShardBy;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ShardKeyExtractor} covering all three extraction modes.
 */
class ShardKeyExtractorTest {

    // -------------------------------------------------------------------------
    // Mode 1: first long parameter (no value on @ShardBy)
    // -------------------------------------------------------------------------

    @Test
    void firstLongParam_extractsCorrectly() throws Exception {
        Method method = Fixture.class.getMethod("byFirstLong", long.class, String.class);
        ShardBy annotation = method.getAnnotation(ShardBy.class);
        long key = ShardKeyExtractor.extract(method, new Object[]{42L, "ignored"}, annotation);
        assertThat(key).isEqualTo(42L);
    }

    @Test
    void firstLongParam_noLongParameter_throws() throws Exception {
        Method method = Fixture.class.getMethod("noLong", String.class);
        ShardBy annotation = method.getAnnotation(ShardBy.class);
        assertThatThrownBy(() -> ShardKeyExtractor.extract(method, new Object[]{"x"}, annotation))
            .isInstanceOf(ShardKeyExtractionException.class);
    }

    // -------------------------------------------------------------------------
    // Mode 2: named parameter
    // -------------------------------------------------------------------------

    @Test
    void namedParam_extractsCorrectly() throws Exception {
        Method method = Fixture.class.getMethod("byName", String.class, long.class);
        ShardBy annotation = method.getAnnotation(ShardBy.class);
        long key = ShardKeyExtractor.extract(method, new Object[]{"unused", 99L}, annotation);
        assertThat(key).isEqualTo(99L);
    }

    // -------------------------------------------------------------------------
    // Mode 3: entity field
    // -------------------------------------------------------------------------

    @Test
    void fromEntity_extractsFieldValue() throws Exception {
        Method method = Fixture.class.getMethod("byEntityField", Fixture.Payment.class);
        ShardBy annotation = method.getAnnotation(ShardBy.class);
        Fixture.Payment payment = new Fixture.Payment(777L);
        long key = ShardKeyExtractor.extract(method, new Object[]{payment}, annotation);
        assertThat(key).isEqualTo(777L);
    }

    @Test
    void fromEntity_missingField_throws() throws Exception {
        Method method = Fixture.class.getMethod("byMissingField", Fixture.Payment.class);
        ShardBy annotation = method.getAnnotation(ShardBy.class);
        assertThatThrownBy(() ->
            ShardKeyExtractor.extract(method, new Object[]{new Fixture.Payment(1L)}, annotation))
            .isInstanceOf(ShardKeyExtractionException.class);
    }

    // -------------------------------------------------------------------------
    // Fixture class (compiled with -parameters via Spring Boot default)
    // -------------------------------------------------------------------------

    static class Fixture {

        @ShardBy
        public void byFirstLong(long accountId, String name) {}

        @ShardBy
        public void noLong(String name) {}

        @ShardBy("accountId")
        public void byName(String ignored, long accountId) {}

        @ShardBy(value = "accountId", fromEntity = true)
        public void byEntityField(Payment payment) {}

        @ShardBy(value = "nonExistentField", fromEntity = true)
        public void byMissingField(Payment payment) {}

        static class Payment {
            public final long accountId;
            Payment(long accountId) { this.accountId = accountId; }
        }
    }
}

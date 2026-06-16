package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the {@link ShardContext} fallback flag introduced in Gap 2.3.
 *
 * <p>Kept separate from {@link ShardContextTest} so the existing test class is
 * not disturbed — each test here calls {@link ShardContext#enableFallback()} in
 * {@code @BeforeEach} and {@code @AfterEach} to ensure the process-wide flag is
 * left in a clean state for other tests.
 */
class ShardContextFallbackTest {

    @BeforeEach
    void ensureFallbackEnabled() {
        ShardContext.enableFallback();
        ShardContext.clear();
    }

    @AfterEach
    void restoreFallback() {
        ShardContext.enableFallback(); // reset for other test classes
        ShardContext.clear();
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    void fallbackIsEnabledByDefault() {
        assertThat(ShardContext.isFallbackAllowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // disableFallback
    // -------------------------------------------------------------------------

    @Test
    void disableFallback_setsFlagToFalse() {
        ShardContext.disableFallback();

        assertThat(ShardContext.isFallbackAllowed()).isFalse();
    }

    @Test
    void disableFallback_isIdempotent() {
        ShardContext.disableFallback();
        ShardContext.disableFallback(); // second call must not throw

        assertThat(ShardContext.isFallbackAllowed()).isFalse();
    }

    // -------------------------------------------------------------------------
    // enableFallback
    // -------------------------------------------------------------------------

    @Test
    void enableFallback_restoresFlagToTrue() {
        ShardContext.disableFallback();
        assertThat(ShardContext.isFallbackAllowed()).isFalse();

        ShardContext.enableFallback();

        assertThat(ShardContext.isFallbackAllowed()).isTrue();
    }

    @Test
    void enableFallback_isIdempotent() {
        ShardContext.enableFallback(); // already enabled
        ShardContext.enableFallback();

        assertThat(ShardContext.isFallbackAllowed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Interaction with shard key and readOnly state
    // -------------------------------------------------------------------------

    @Test
    void disableFallback_doesNotAffectShardKey() {
        ShardContext.set(42L);

        ShardContext.disableFallback();

        assertThat(ShardContext.get())
            .as("disableFallback must not affect the shard key in context")
            .isEqualTo(42L);
    }

    @Test
    void clear_doesNotAffectFallbackFlag() {
        ShardContext.disableFallback();
        ShardContext.set(10L);

        ShardContext.clear(); // clears shard key + readOnly but NOT the fallback flag

        assertThat(ShardContext.isFallbackAllowed())
            .as("ShardContext.clear() must not reset the fallbackAllowed flag")
            .isFalse();
        assertThat(ShardContext.get())
            .as("Shard key must be cleared")
            .isNull();
    }

    // -------------------------------------------------------------------------
    // Thread visibility — volatile ensures cross-thread visibility
    // -------------------------------------------------------------------------

    @Test
    void disableFallback_visibleFromOtherThread() throws InterruptedException {
        ShardContext.disableFallback();

        boolean[] seenInThread = {true}; // default to true so we catch a failure
        Thread t = new Thread(() -> seenInThread[0] = ShardContext.isFallbackAllowed());
        t.start();
        t.join();

        assertThat(seenInThread[0])
            .as("disableFallback() (volatile write) must be visible from other threads")
            .isFalse();
    }
}

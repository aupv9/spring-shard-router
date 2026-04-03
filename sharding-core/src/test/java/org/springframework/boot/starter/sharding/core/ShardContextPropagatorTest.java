package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ShardContextPropagatorTest {

    @AfterEach
    void cleanup() {
        ShardContext.clear();
    }

    // ── wrap(Supplier) ────────────────────────────────────────────────────────

    @Test
    void shouldWrapSupplierWithShardContext() throws Exception {
        ShardContext.set(10L);
        var wrapped = ShardContextPropagator.wrap(() -> ShardContext.get());
        // Run in another thread
        AtomicReference<Long> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(wrapped.get()));
        t.start(); t.join();
        assertEquals(10L, result.get());
    }

    @Test
    void shouldClearContextAfterSupplier() throws Exception {
        ShardContext.set(20L);
        var wrapped = ShardContextPropagator.wrap(() -> "result");
        AtomicReference<Long> afterExec = new AtomicReference<>();
        Thread t = new Thread(() -> { wrapped.get(); afterExec.set(ShardContext.get()); });
        t.start(); t.join();
        assertNull(afterExec.get(), "ShardContext must be cleared after supplier runs");
    }

    @Test
    void shouldClearContextWhenSupplierThrows() throws Exception {
        ShardContext.set(30L);
        var wrapped = ShardContextPropagator.wrap(() -> { throw new RuntimeException("fail"); });
        AtomicReference<Long> afterExec = new AtomicReference<>(Long.MIN_VALUE);
        Thread t = new Thread(() -> {
            try { wrapped.get(); } catch (RuntimeException ignored) {}
            afterExec.set(ShardContext.get());
        });
        t.start(); t.join();
        assertNull(afterExec.get(), "ShardContext must be cleared even when supplier throws");
    }

    @Test
    void shouldNotSetContextWhenNullShardKey() throws Exception {
        // No ShardContext set
        var wrapped = ShardContextPropagator.wrap(() -> ShardContext.get());
        AtomicReference<Long> result = new AtomicReference<>(Long.MIN_VALUE);
        Thread t = new Thread(() -> result.set(wrapped.get()));
        t.start(); t.join();
        assertNull(result.get(), "Worker should have null shard key when parent had none");
    }

    // ── wrap(Callable) ────────────────────────────────────────────────────────

    @Test
    void shouldWrapCallableWithShardContext() throws Exception {
        ShardContext.set(50L);
        var wrapped = ShardContextPropagator.wrap(() -> ShardContext.get());
        AtomicReference<Long> result = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try { result.set(wrapped.call()); } catch (Exception e) { fail(e); }
        });
        t.start(); t.join();
        assertEquals(50L, result.get());
    }

    // ── wrap(Runnable) ────────────────────────────────────────────────────────

    @Test
    void shouldWrapRunnableWithShardContext() throws Exception {
        ShardContext.set(60L);
        AtomicLong captured = new AtomicLong(-1);
        var wrapped = ShardContextPropagator.wrap((Runnable) () ->
            captured.set(ShardContext.get() != null ? ShardContext.get() : -1));
        Thread t = new Thread(wrapped);
        t.start(); t.join();
        assertEquals(60L, captured.get());
    }

    @Test
    void shouldClearContextAfterRunnable() throws Exception {
        ShardContext.set(70L);
        var wrapped = ShardContextPropagator.wrap((Runnable) () -> {});
        AtomicReference<Long> afterExec = new AtomicReference<>(Long.MIN_VALUE);
        Thread t = new Thread(() -> { wrapped.run(); afterExec.set(ShardContext.get()); });
        t.start(); t.join();
        assertNull(afterExec.get());
    }

    // ── wrap(Executor) ────────────────────────────────────────────────────────

    @Test
    void shouldWrapExecutorSoAllTasksInheritShardKey() throws Exception {
        ShardContext.set(80L);
        AtomicLong task1Key = new AtomicLong(-1);
        AtomicLong task2Key = new AtomicLong(-1);

        Executor directExecutor = Runnable::run; // runs on calling thread
        Executor wrapped = ShardContextPropagator.wrap(directExecutor);

        // Each task captures the shard key at submission time (80L)
        wrapped.execute(() -> task1Key.set(ShardContext.get() != null ? ShardContext.get() : -1));
        wrapped.execute(() -> task2Key.set(ShardContext.get() != null ? ShardContext.get() : -1));

        assertEquals(80L, task1Key.get());
        assertEquals(80L, task2Key.get());
    }

    // ── supplyAsync ───────────────────────────────────────────────────────────

    @Test
    void shouldSupplyAsyncWithShardContext() throws Exception {
        ShardContext.set(90L);
        var future = ShardContextPropagator.supplyAsync(() -> ShardContext.get());
        assertEquals(90L, future.get());
    }

    @Test
    void shouldRunAsyncWithShardContext() throws Exception {
        ShardContext.set(100L);
        AtomicLong captured = new AtomicLong(-1);
        var future = ShardContextPropagator.runAsync(
            () -> captured.set(ShardContext.get() != null ? ShardContext.get() : -1));
        future.get();
        assertEquals(100L, captured.get());
    }

    @Test
    void shouldCaptureShardKeyAtSubmissionNotExecution() throws Exception {
        ShardContext.set(110L);
        // Wrap the supplier while ShardContext is 110
        var wrapped = ShardContextPropagator.wrap(() -> ShardContext.get());

        // Change ShardContext before supplier executes
        ShardContext.set(999L);

        AtomicReference<Long> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(wrapped.get()));
        t.start(); t.join();

        // Should see 110 (captured at wrap time), not 999
        assertEquals(110L, result.get(), "Shard key must be captured at wrap time, not execution time");
    }
}

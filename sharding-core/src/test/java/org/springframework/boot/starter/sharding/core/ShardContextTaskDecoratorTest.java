package org.springframework.boot.starter.sharding.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ShardContextTaskDecoratorTest {

    private final ShardContextTaskDecorator decorator = new ShardContextTaskDecorator();

    @AfterEach
    void cleanup() {
        ShardContext.clear();
    }

    @Test
    void shouldPropagateShardContextToDecoratedRunnable() throws InterruptedException {
        long shardKey = 42L;
        ShardContext.set(shardKey);

        AtomicLong capturedKey = new AtomicLong(-1);
        Runnable decorated = decorator.decorate(() -> capturedKey.set(ShardContext.get() != null ? ShardContext.get() : -1));

        // Run in a separate thread (simulating @Async thread pool)
        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertEquals(shardKey, capturedKey.get(), "Worker thread should see the parent thread's shard key");
    }

    @Test
    void shouldClearContextAfterRunnableCompletes() throws InterruptedException {
        ShardContext.set(99L);

        AtomicReference<Long> keyAfterExecution = new AtomicReference<>();
        Runnable decorated = decorator.decorate(() -> {
            // do nothing
        });

        Thread worker = new Thread(() -> {
            decorated.run();
            keyAfterExecution.set(ShardContext.get());
        });
        worker.start();
        worker.join();

        assertNull(keyAfterExecution.get(), "ShardContext must be cleared after decorated runnable finishes");
    }

    @Test
    void shouldNotSetContextWhenParentHasNoShardKey() throws InterruptedException {
        // No ShardContext.set() in parent thread
        assertNull(ShardContext.get());

        AtomicReference<Long> capturedInWorker = new AtomicReference<>(Long.MIN_VALUE);
        Runnable decorated = decorator.decorate(() -> capturedInWorker.set(ShardContext.get()));

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertNull(capturedInWorker.get(), "Worker should have null shard key when parent had none");
    }

    @Test
    void shouldClearContextEvenWhenRunnableThrows() throws InterruptedException {
        ShardContext.set(55L);

        Runnable decorated = decorator.decorate(() -> {
            throw new RuntimeException("boom");
        });

        AtomicReference<Long> keyAfterException = new AtomicReference<>(Long.MIN_VALUE);
        Thread worker = new Thread(() -> {
            try {
                decorated.run();
            } catch (RuntimeException ignored) {
                // expected
            }
            keyAfterException.set(ShardContext.get());
        });
        worker.start();
        worker.join();

        assertNull(keyAfterException.get(), "ShardContext must be cleared even if runnable throws");
    }

    @Test
    void shouldIsolateShardKeyBetweenWorkerThreads() throws InterruptedException {
        long parentKey = 777L;
        ShardContext.set(parentKey);

        Runnable decorated = decorator.decorate(() -> {
            // Verify worker sees parent key
            assertEquals(parentKey, ShardContext.get());
            // Modify it within worker — must not affect parent
            ShardContext.set(999L);
        });

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        // Parent thread key should be unchanged
        assertEquals(parentKey, ShardContext.get(), "Parent thread's ShardContext must not be affected by worker");
    }
}

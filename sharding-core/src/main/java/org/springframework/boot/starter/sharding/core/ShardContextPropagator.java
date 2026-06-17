package org.springframework.boot.starter.sharding.core;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Utility for propagating the current shard context into async constructs
 * where {@link ShardContextTaskDecorator} cannot be used automatically —
 * for example, manual {@link CompletableFuture} chains or reactive pipelines.
 *
 * <p>Usage:
 * <pre>{@code
 * // CompletableFuture — shard key captured now, applied in the worker thread
 * CompletableFuture<BigDecimal> balance =
 *     ShardContextPropagator.supplyAsync(accountId, () -> repo.findBalance(accountId));
 *
 * // Wrap an existing Executor so every task it runs inherits the shard key
 * Executor shardAwareExecutor = ShardContextPropagator.wrap(myExecutor);
 * CompletableFuture.runAsync(() -> doWork(), shardAwareExecutor);
 * }</pre>
 */
public final class ShardContextPropagator {

    private ShardContextPropagator() {}

    /**
     * Wrap a {@link Supplier} so it runs with the current shard context
     * captured at call time, not at execution time.
     */
    public static <T> Supplier<T> wrap(Supplier<T> supplier) {
        Long shardKey = ShardContext.get();
        return () -> {
            Long previous = ShardContext.get();
            try {
                applyKey(shardKey);
                return supplier.get();
            } finally {
                restoreKey(previous);
            }
        };
    }

    /**
     * Wrap a {@link Callable} so it runs with the current shard context.
     *
     * <p>Named distinctly from {@link #wrap(Supplier)} because a bare lambda
     * ({@code () -> value}) is assignment-compatible with both {@code Supplier}
     * and {@code Callable}, which would make an overloaded {@code wrap} ambiguous
     * at the call site.
     */
    public static <T> Callable<T> wrapCallable(Callable<T> callable) {
        Long shardKey = ShardContext.get();
        return () -> {
            Long previous = ShardContext.get();
            try {
                applyKey(shardKey);
                return callable.call();
            } finally {
                restoreKey(previous);
            }
        };
    }

    /**
     * Wrap a {@link Runnable} so it runs with the current shard context.
     */
    public static Runnable wrap(Runnable runnable) {
        Long shardKey = ShardContext.get();
        return () -> {
            Long previous = ShardContext.get();
            try {
                applyKey(shardKey);
                runnable.run();
            } finally {
                restoreKey(previous);
            }
        };
    }

    /** Set the captured shard key for the worker, or clear it if none was captured. */
    private static void applyKey(Long shardKey) {
        if (shardKey != null) {
            ShardContext.set(shardKey);
        } else {
            ShardContext.clear();
        }
    }

    /**
     * Restore the shard key that was present before the wrapped task ran.
     *
     * <p>Saving and restoring (rather than unconditionally clearing) keeps the
     * propagator correct when the task runs on a thread that already carried a
     * shard context — e.g. a same-thread/direct executor — instead of wiping the
     * caller's context.
     */
    private static void restoreKey(Long previous) {
        if (previous != null) {
            ShardContext.set(previous);
        } else {
            ShardContext.clear();
        }
    }

    /**
     * Wrap an {@link Executor} so every task submitted through it automatically
     * inherits the shard key present at submission time.
     */
    public static Executor wrap(Executor executor) {
        return runnable -> executor.execute(wrap(runnable));
    }

    /**
     * Submit a task as a {@link CompletableFuture} using the common fork-join pool,
     * with the current shard context propagated.
     */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(wrap(supplier));
    }

    /**
     * Submit a task as a {@link CompletableFuture} using the given executor,
     * with the current shard context propagated.
     */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
        return CompletableFuture.supplyAsync(wrap(supplier), executor);
    }

    /**
     * Run a task asynchronously using the common fork-join pool,
     * with the current shard context propagated.
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(wrap(runnable));
    }

    /**
     * Run a task asynchronously using the given executor,
     * with the current shard context propagated.
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        return CompletableFuture.runAsync(wrap(runnable), executor);
    }
}

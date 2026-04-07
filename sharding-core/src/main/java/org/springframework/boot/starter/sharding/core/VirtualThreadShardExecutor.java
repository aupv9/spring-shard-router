package org.springframework.boot.starter.sharding.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Factory for an {@link ExecutorService} used by scatter-gather and other parallel
 * shard operations.
 *
 * <p>On <b>JDK&nbsp;21+</b> the executor uses <em>virtual threads</em> (Project Loom),
 * giving near-zero per-thread overhead and making it safe to issue one virtual thread
 * per shard regardless of shard count.
 *
 * <p>On <b>JDK&nbsp;17/18/19/20</b> (where virtual threads are either unavailable or
 * only in preview) the executor falls back to a standard cached thread pool.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Inject as a bean or create locally:
 * ExecutorService executor = VirtualThreadShardExecutor.create("shard-scatter");
 *
 * // Hand off to ShardScatterGatherTemplate:
 * new ShardScatterGatherTemplate(shardRouter, executor);
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 * The returned {@link ExecutorService} must be shut down by the caller when no longer
 * needed. For Spring-managed beans use {@code @PreDestroy} or {@code DisposableBean}.
 */
public final class VirtualThreadShardExecutor {

    private VirtualThreadShardExecutor() {}

    /**
     * Create an executor optimised for the current JVM.
     *
     * @param threadNamePrefix prefix used when naming threads (informational)
     * @return a new {@link ExecutorService}; caller is responsible for shutdown
     */
    public static ExecutorService create(String threadNamePrefix) {
        if (isVirtualThreadsAvailable()) {
            return newVirtualThreadExecutor(threadNamePrefix);
        }
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName(threadNamePrefix + "-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Returns {@code true} if the JVM supports virtual threads (JDK 21+).
     */
    public static boolean isVirtualThreadsAvailable() {
        return Runtime.version().feature() >= 21;
    }

    /**
     * Called only when JDK 21+ is confirmed; uses reflection to avoid compile-time
     * dependency on preview/newer APIs, keeping the module source-compatible with JDK 17.
     */
    private static ExecutorService newVirtualThreadExecutor(String prefix) {
        try {
            // Executors.newVirtualThreadPerTaskExecutor() — JDK 21 API
            var method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) method.invoke(null);
        } catch (Exception e) {
            // Should never happen when isVirtualThreadsAvailable() is true
            return Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setName(prefix + "-fallback-" + t.getId());
                t.setDaemon(true);
                return t;
            });
        }
    }
}

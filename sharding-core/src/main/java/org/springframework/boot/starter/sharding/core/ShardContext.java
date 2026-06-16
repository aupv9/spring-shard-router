package org.springframework.boot.starter.sharding.core;

/**
 * Thread-local context for shard key management.
 * Ensures shard key is available throughout the request lifecycle.
 *
 * <p>Also carries an optional read-only flag used by read/write splitting:
 * when {@code true}, {@code ReadWriteRoutingDataSource} routes to a read replica
 * instead of the primary. The flag integrates automatically with Spring's
 * {@code @Transactional(readOnly=true)}.
 *
 * <p>A process-wide {@code allowFallback} flag controls whether routing datasources
 * are permitted to fall back to shard-0 when no shard key is set. During startup
 * (schema validation, pool probing) this fallback is needed; during normal request
 * processing it masks bugs where the caller forgot to set a shard key. Call
 * {@link #disableFallback()} once the application context is fully started.
 */
public class ShardContext {

    private static final ThreadLocal<Long>    SHARD_KEY = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> READ_ONLY = new ThreadLocal<>();

    /**
     * When {@code true} (the default), routing datasources silently fall back to
     * shard-0 when no shard key is in context. This is required during Spring Boot
     * startup (Hibernate schema validation, HikariCP connection probing).
     *
     * <p>Set to {@code false} via {@link #disableFallback()} after the application
     * context is fully started so that missing shard keys throw instead of silently
     * routing to the wrong shard.
     */
    private static volatile boolean fallbackAllowed = true;

    // -------------------------------------------------------------------------
    // Shard key management
    // -------------------------------------------------------------------------

    /**
     * Set shard key for current thread.
     * @param key the shard key
     */
    public static void set(long key) {
        SHARD_KEY.set(key);
    }

    /**
     * Get shard key for current thread.
     * @return shard key or null if not set
     */
    public static Long get() {
        return SHARD_KEY.get();
    }

    /**
     * Clear shard key and read-only flag for current thread.
     * Should be called in a finally block to prevent memory leaks.
     */
    public static void clear() {
        SHARD_KEY.remove();
        READ_ONLY.remove();
    }

    /**
     * Execute code block with shard key context.
     * Automatically clears context after execution.
     * @param shardKey the shard key
     * @param runnable code to execute
     */
    public static void execute(long shardKey, Runnable runnable) {
        try {
            set(shardKey);
            runnable.run();
        } finally {
            clear();
        }
    }

    /**
     * Check if shard key is set for current thread.
     * @return true if shard key is set
     */
    public static boolean isSet() {
        return SHARD_KEY.get() != null;
    }

    // -------------------------------------------------------------------------
    // Read-only flag (for read/write splitting)
    // -------------------------------------------------------------------------

    /**
     * Mark current thread as performing a read-only operation.
     * When set to {@code true}, read/write splitting routes to a replica DataSource.
     *
     * @param readOnly {@code true} to route to replica; {@code false} or unset for primary
     */
    public static void setReadOnly(boolean readOnly) {
        if (readOnly) {
            READ_ONLY.set(Boolean.TRUE);
        } else {
            READ_ONLY.remove();
        }
    }

    /**
     * Returns {@code true} if the current thread has explicitly requested read-only routing.
     * Note: read/write splitting also checks Spring's transaction read-only hint automatically.
     */
    public static boolean isReadOnly() {
        return Boolean.TRUE.equals(READ_ONLY.get());
    }

    /**
     * Clear only the read-only flag without affecting the shard key.
     */
    public static void clearReadOnly() {
        READ_ONLY.remove();
    }

    // -------------------------------------------------------------------------
    // Fallback control (startup vs. runtime safety)
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if routing datasources may fall back to shard-0 when no
     * shard key is set. Always {@code true} during startup; {@code false} after
     * {@link #disableFallback()} is called.
     */
    public static boolean isFallbackAllowed() {
        return fallbackAllowed;
    }

    /**
     * Disable the shard-0 silent fallback. Call this once the application context
     * is fully started (e.g. in an {@code ApplicationReadyEvent} listener registered
     * by {@code ShardingAutoConfiguration}).
     *
     * <p>After this call, any {@code getConnection()} on a routing datasource with no
     * shard key in context will throw {@link MissingShardKeyException} instead of
     * silently routing to shard-0.
     */
    public static void disableFallback() {
        fallbackAllowed = false;
    }

    /**
     * Re-enable the shard-0 fallback. Primarily for use in tests that need to reset
     * the flag between test cases.
     */
    public static void enableFallback() {
        fallbackAllowed = true;
    }
}

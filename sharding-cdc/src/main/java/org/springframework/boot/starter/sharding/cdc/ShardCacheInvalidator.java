package org.springframework.boot.starter.sharding.cdc;

import java.util.logging.Logger;

/**
 * {@link ShardChangeEventListener} that invalidates cross-shard query cache entries
 * when a write is detected on any shard.
 *
 * <p>Integrate with a cache implementation (e.g., Caffeine, Spring Cache) by overriding
 * {@link #invalidate(ShardChangeEvent)} or by injecting a {@code CacheManager}.
 *
 * <p>The default implementation logs the invalidation event. Extend this class and
 * inject your cache manager to perform actual eviction:
 *
 * <pre>{@code
 * @Component
 * public class MyCacheInvalidator extends ShardCacheInvalidator {
 *     private final CacheManager cacheManager;
 *     // constructor injection...
 *
 *     @Override
 *     protected void invalidate(ShardChangeEvent event) {
 *         cacheManager.getCache("scatter-gather").clear();
 *     }
 * }
 * }</pre>
 */
public class ShardCacheInvalidator implements ShardChangeEventListener {

    private static final Logger log =
        Logger.getLogger(ShardCacheInvalidator.class.getName());

    @Override
    public final void onShardChange(ShardChangeEvent event) {
        if (event.operation() != ShardChangeEvent.Operation.INSERT
            && event.operation() != ShardChangeEvent.Operation.UPDATE
            && event.operation() != ShardChangeEvent.Operation.DELETE) {
            return; // Only invalidate on data mutations
        }
        invalidate(event);
    }

    /**
     * Perform cache invalidation for the given change event.
     * Override in a subclass to integrate with a specific cache implementation.
     *
     * @param event the change event that triggered the invalidation
     */
    protected void invalidate(ShardChangeEvent event) {
        log.fine("Cache invalidation triggered: shard=" + event.shardName()
            + " table=" + event.tableName()
            + " op=" + event.operation());
    }
}

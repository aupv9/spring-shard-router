package org.springframework.boot.starter.sharding.cdc;

/**
 * Listener for {@link ShardChangeEvent}s produced by a CDC producer.
 *
 * <p>Implement this interface and register the bean in the Spring context to receive
 * real-time change notifications from any configured shard.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link ShardMigrationConsistencyChecker} — reconciles double-write gaps</li>
 *   <li>{@link ShardCacheInvalidator} — evicts stale cross-shard query cache entries</li>
 * </ul>
 */
@FunctionalInterface
public interface ShardChangeEventListener {

    /**
     * Handle a shard change event.
     *
     * <p>Implementations must be non-blocking and should not throw unchecked exceptions
     * that would interrupt the event delivery loop. Errors should be logged and swallowed.
     *
     * @param event the change event; never null
     */
    void onShardChange(ShardChangeEvent event);
}

package org.springframework.boot.starter.sharding.cdc;

/**
 * Consumer SPI for shard change events.
 *
 * <p>Implement this interface and register the bean in Spring context to receive
 * every {@link ShardCdcEvent} captured by a {@link ShardCdcSource}.
 *
 * <p>The {@link ShardCdcDispatcher} calls {@link #onEvent} synchronously for each
 * event. Implementations should be fast and non-blocking; for heavy work, hand off
 * to an executor or messaging system inside {@link #onEvent}.
 *
 * <p>Example — log every INSERT on the {@code transactions} table:
 * <pre>{@code
 * @Component
 * public class TransactionAuditListener implements ShardCdcListener {
 *
 *     @Override
 *     public boolean accepts(ShardCdcEvent event) {
 *         return "transactions".equals(event.getTable())
 *             && event.getOperation() == ShardCdcEvent.Operation.INSERT;
 *     }
 *
 *     @Override
 *     public void onEvent(ShardCdcEvent event) {
 *         log.info("New transaction on shard {}: {}", event.getShardIndex(), event.getAfter());
 *     }
 * }
 * }</pre>
 */
public interface ShardCdcListener {

    /**
     * Filter predicate — return {@code true} to receive this event, {@code false} to skip.
     * The default accepts every event; override to narrow the subscription.
     */
    default boolean accepts(ShardCdcEvent event) {
        return true;
    }

    /**
     * Handle a change event from a shard.
     *
     * @param event the captured change
     */
    void onEvent(ShardCdcEvent event);
}

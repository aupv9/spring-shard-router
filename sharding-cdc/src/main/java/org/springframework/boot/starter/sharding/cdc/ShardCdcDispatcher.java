package org.springframework.boot.starter.sharding.cdc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Central event bus between {@link ShardCdcSource}s and {@link ShardCdcListener}s.
 *
 * <p>Sources call {@link #dispatch(ShardCdcEvent)} for each captured change. The
 * dispatcher fans the event out to every registered listener whose
 * {@link ShardCdcListener#accepts} filter returns {@code true}.
 *
 * <p>Listener failures are caught and logged — one bad listener does not block
 * the others. If you need guaranteed delivery, your listener should write to a
 * durable store (queue, outbox table) inside {@link ShardCdcListener#onEvent}.
 *
 * <p>Thread safety: {@link #dispatch} may be called from multiple source threads
 * concurrently. The listener list is set once at construction time and never
 * mutated, so no synchronisation is needed on the dispatch path.
 */
public class ShardCdcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ShardCdcDispatcher.class);

    private final List<ShardCdcListener> listeners;

    public ShardCdcDispatcher(List<ShardCdcListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    /**
     * Dispatch {@code event} to every listener that {@link ShardCdcListener#accepts accepts} it.
     *
     * @param event the captured change event
     */
    public void dispatch(ShardCdcEvent event) {
        for (ShardCdcListener listener : listeners) {
            try {
                if (listener.accepts(event)) {
                    listener.onEvent(event);
                }
            } catch (Exception ex) {
                log.error("[cdc] listener {} threw on event {}: {}",
                    listener.getClass().getSimpleName(), event, ex.getMessage(), ex);
            }
        }
    }

    /** Number of registered listeners (useful for diagnostics). */
    public int listenerCount() {
        return listeners.size();
    }
}

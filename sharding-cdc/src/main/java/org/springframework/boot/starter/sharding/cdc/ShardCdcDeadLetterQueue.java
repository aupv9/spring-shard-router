package org.springframework.boot.starter.sharding.cdc;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dead-letter queue contract for {@link ShardChangeEvent}s that could not be processed
 * by any registered {@link ShardChangeEventListener}.
 *
 * <p>An event is sent to the DLQ only when <em>all</em> listeners have thrown an
 * exception — a partial success (at least one listener succeeded) is not sent to the DLQ.
 *
 * <h3>Built-in implementations</h3>
 * <ul>
 *   <li>{@link #logging()} — logs at WARNING level and discards. Safe no-op for dev/test.</li>
 *   <li>{@link #throwing()} — re-throws the original cause wrapped in
 *       {@link ShardCdcProcessingException}. Useful to halt the consumer on failure.</li>
 * </ul>
 *
 * <h3>Production wiring</h3>
 * <pre>{@code
 * // Kafka DLQ:
 * ShardCdcDeadLetterQueue kafkaDlq = (event, cause) ->
 *     kafkaTemplate.send("shard-cdc-dlq", event.shardName(), objectMapper.writeValueAsString(event));
 *
 * // Redis list:
 * ShardCdcDeadLetterQueue redisDlq = (event, cause) ->
 *     redisTemplate.opsForList().leftPush("cdc:dlq:" + event.shardName(),
 *         objectMapper.writeValueAsString(event));
 * }</pre>
 */
@FunctionalInterface
public interface ShardCdcDeadLetterQueue {

    /**
     * Called when an event could not be delivered to any listener.
     *
     * @param event the failed change event
     * @param cause the last exception thrown (from the last failing listener)
     */
    void send(ShardChangeEvent event, Exception cause);

    // -------------------------------------------------------------------------
    // Built-in factories
    // -------------------------------------------------------------------------

    /**
     * Returns a no-op DLQ that logs the failed event at WARNING level and discards it.
     * Use this in development, tests, or when failures are acceptable.
     */
    static ShardCdcDeadLetterQueue logging() {
        Logger log = Logger.getLogger(ShardCdcDeadLetterQueue.class.getName());
        return (event, cause) -> log.log(Level.WARNING,
            "CDC event delivered to DLQ (no listener succeeded) — shard=" + event.shardName()
                + " table=" + event.tableName() + " op=" + event.operation()
                + " shardKey=" + event.shardKey(),
            cause);
    }

    /**
     * Returns a DLQ that re-throws the cause wrapped in {@link ShardCdcProcessingException}.
     * Use this when you want the CDC consumer thread to halt on any unhandled failure.
     */
    static ShardCdcDeadLetterQueue throwing() {
        return (event, cause) -> {
            throw new ShardCdcProcessingException(
                "CDC event processing failed for shard=" + event.shardName()
                    + " table=" + event.tableName() + " op=" + event.operation(), cause);
        };
    }

    /**
     * Thrown by the {@link #throwing()} DLQ implementation.
     */
    class ShardCdcProcessingException extends RuntimeException {
        public ShardCdcProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

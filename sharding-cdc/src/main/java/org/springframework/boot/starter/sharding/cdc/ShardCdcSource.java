package org.springframework.boot.starter.sharding.cdc;

/**
 * SPI for CDC sources that emit {@link ShardCdcEvent}s.
 *
 * <p>A CDC source is responsible for connecting to a shard's database, receiving
 * row-level change notifications (via polling, Debezium, Kafka, etc.), converting
 * them into {@link ShardCdcEvent}s, and pushing them to a {@link ShardCdcDispatcher}.
 *
 * <p>The library ships with two implementations:
 * <ul>
 *   <li>{@link PollingShardCdcSource} — JDBC polling on an {@code updated_at} column.
 *       Zero external dependencies. Suitable for low-throughput environments.</li>
 *   <li>{@link OutboxShardCdcSource} — Reads from a dedicated outbox table written by
 *       application code. Reliable and works with any RDBMS.</li>
 * </ul>
 *
 * <p>Debezium and Kafka-based sources are planned for a future release. Because those
 * require optional classpath dependencies, they will live in separate sub-packages
 * guarded by {@code @ConditionalOnClass}.
 */
public interface ShardCdcSource {

    /**
     * Start capturing changes. The source begins polling or listening and forwards
     * events to the supplied dispatcher. This method should be non-blocking — spawn
     * a background thread if necessary and return immediately.
     *
     * @param dispatcher receiver for captured events
     */
    void start(ShardCdcDispatcher dispatcher);

    /**
     * Stop capturing changes and release all resources (connections, threads, etc.).
     * Called by the container on application shutdown.
     */
    void stop();

    /** Human-readable name for logging and metrics tagging. */
    String name();
}

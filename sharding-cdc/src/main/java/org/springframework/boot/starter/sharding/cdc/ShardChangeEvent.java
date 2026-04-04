package org.springframework.boot.starter.sharding.cdc;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a single data-change event captured from a shard's WAL (Write-Ahead Log).
 *
 * <p>Produced by {@link DebeziumShardChangeProducer} or {@link KafkaShardChangeProducer}
 * and delivered to all registered {@link ShardChangeEventListener}s.
 */
public record ShardChangeEvent(
    /** Name of the shard where the change occurred (e.g., {@code "shard-0"}). */
    String shardName,

    /** Unqualified table name (e.g., {@code "accounts"}). */
    String tableName,

    /** Type of database operation. */
    Operation operation,

    /**
     * The shard key value extracted from the changed row.
     * May be {@code -1} if the shard key column is not present in the event payload.
     */
    long shardKey,

    /**
     * Full row payload — column name → value.
     * For {@link Operation#DELETE}, contains the before-image if available.
     */
    Map<String, Object> payload,

    /** Wall-clock time when the change was captured. */
    Instant timestamp
) {

    public enum Operation {
        INSERT, UPDATE, DELETE
    }

    /** Convenience factory for tests. */
    public static ShardChangeEvent of(String shardName, String tableName, Operation operation,
                                       long shardKey, Map<String, Object> payload) {
        return new ShardChangeEvent(shardName, tableName, operation, shardKey, payload, Instant.now());
    }
}

package org.springframework.boot.starter.sharding.cdc;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a single data-change event captured from a shard's WAL (Write-Ahead Log).
 *
 * <p>Produced by {@link DebeziumShardChangeProducer} or {@link KafkaShardChangeProducer}
 * and delivered to all registered {@link ShardChangeEventListener}s.
 *
 * <p><b>Image semantics by operation:</b>
 * <ul>
 *   <li>{@link Operation#INSERT}  — {@code afterImage} = new row; {@code beforeImage} = empty</li>
 *   <li>{@link Operation#UPDATE}  — {@code afterImage} = new values; {@code beforeImage} = old values</li>
 *   <li>{@link Operation#DELETE}  — {@code beforeImage} = deleted row; {@code afterImage} = empty
 *       (requires {@code REPLICA IDENTITY FULL} on the table for full before-image)</li>
 * </ul>
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
     * May be {@code -1} if the shard key column is not present in either image.
     */
    long shardKey,

    /**
     * Row values <em>after</em> the change (INSERT, UPDATE).
     * Empty map for DELETE.
     */
    Map<String, Object> afterImage,

    /**
     * Row values <em>before</em> the change (UPDATE, DELETE).
     * Empty map for INSERT.
     * For UPDATE/DELETE, requires PostgreSQL {@code REPLICA IDENTITY FULL}
     * ({@code ALTER TABLE accounts REPLICA IDENTITY FULL}) to capture all columns;
     * otherwise only primary key columns are available.
     */
    Map<String, Object> beforeImage,

    /** Wall-clock time when the change was captured. */
    Instant timestamp
) {

    public enum Operation {
        INSERT, UPDATE, DELETE
    }

    /** Convenience factory for INSERT events (no before-image). */
    public static ShardChangeEvent insert(String shardName, String tableName,
                                           long shardKey, Map<String, Object> afterImage) {
        return new ShardChangeEvent(shardName, tableName, Operation.INSERT,
            shardKey, afterImage, Map.of(), Instant.now());
    }

    /** Convenience factory for UPDATE events. */
    public static ShardChangeEvent update(String shardName, String tableName,
                                           long shardKey, Map<String, Object> beforeImage,
                                           Map<String, Object> afterImage) {
        return new ShardChangeEvent(shardName, tableName, Operation.UPDATE,
            shardKey, afterImage, beforeImage, Instant.now());
    }

    /** Convenience factory for DELETE events (no after-image). */
    public static ShardChangeEvent delete(String shardName, String tableName,
                                           long shardKey, Map<String, Object> beforeImage) {
        return new ShardChangeEvent(shardName, tableName, Operation.DELETE,
            shardKey, Map.of(), beforeImage, Instant.now());
    }

    /**
     * Returns the most relevant payload for this event:
     * {@code afterImage} for INSERT/UPDATE, {@code beforeImage} for DELETE.
     */
    public Map<String, Object> payload() {
        return operation == Operation.DELETE ? beforeImage : afterImage;
    }

    /** Convenience factory for tests with explicit timestamp. */
    public static ShardChangeEvent of(String shardName, String tableName, Operation operation,
                                       long shardKey, Map<String, Object> afterImage) {
        return new ShardChangeEvent(shardName, tableName, operation,
            shardKey, afterImage, Map.of(), Instant.now());
    }
}

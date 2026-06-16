package org.springframework.boot.starter.sharding.cdc;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable representation of a single row-level change captured from a shard.
 *
 * <p>Produced by a {@link ShardCdcSource} and delivered to every registered
 * {@link ShardCdcListener}. Contains enough context for listeners to identify:
 * <ul>
 *   <li>Which shard the change came from ({@link #shardIndex})</li>
 *   <li>Which table and operation ({@link #table}, {@link #operation})</li>
 *   <li>The row state before and after the change ({@link #before}, {@link #after})</li>
 *   <li>The extracted shard key value ({@link #shardKey}) — available when the
 *       shard key column is present in the changed row</li>
 * </ul>
 *
 * <p>Column values are raw JDBC types (String, Long, BigDecimal, etc.) as returned by
 * the underlying CDC source. Listeners are responsible for type-casting.
 */
public final class ShardCdcEvent {

    /** Type of DML operation captured. */
    public enum Operation { INSERT, UPDATE, DELETE }

    private final int               shardIndex;
    private final String            table;
    private final Operation         operation;
    private final Map<String,Object> before;    // null for INSERT
    private final Map<String,Object> after;     // null for DELETE
    private final Long              shardKey;   // null when not resolvable
    private final Instant           capturedAt;

    public ShardCdcEvent(int shardIndex, String table, Operation operation,
                          Map<String, Object> before, Map<String, Object> after,
                          Long shardKey) {
        this.shardIndex  = shardIndex;
        this.table       = table;
        this.operation   = operation;
        this.before      = before != null ? Map.copyOf(before) : null;
        this.after       = after  != null ? Map.copyOf(after)  : null;
        this.shardKey    = shardKey;
        this.capturedAt  = Instant.now();
    }

    public int                  getShardIndex()  { return shardIndex; }
    public String               getTable()       { return table; }
    public Operation            getOperation()   { return operation; }
    public Map<String, Object>  getBefore()      { return before; }
    public Map<String, Object>  getAfter()       { return after; }
    public Long                 getShardKey()    { return shardKey; }
    public Instant              getCapturedAt()  { return capturedAt; }

    /** Convenience: the new row state for INSERT/UPDATE, or the old state for DELETE. */
    public Map<String, Object> effectiveRow() {
        return after != null ? after : before;
    }

    @Override
    public String toString() {
        return "ShardCdcEvent{shardIndex=" + shardIndex
            + ", table='" + table + '\''
            + ", operation=" + operation
            + ", shardKey=" + shardKey
            + ", capturedAt=" + capturedAt + '}';
    }
}

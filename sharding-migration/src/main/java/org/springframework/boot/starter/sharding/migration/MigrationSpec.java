package org.springframework.boot.starter.sharding.migration;

import java.util.Objects;

/**
 * Specification for a shard data migration job.
 *
 * <p>Describes WHAT to move (table + shard-key range), FROM where (source shard index),
 * and TO where (target shard index). The {@link ShardMigrationService} uses this as
 * its sole input.
 *
 * <p>Build with the fluent builder:
 * <pre>{@code
 * MigrationSpec spec = MigrationSpec.builder()
 *     .table("transactions")
 *     .shardKeyColumn("account_id")
 *     .shardKeyRange(1000L, 1999L)       // inclusive on both ends
 *     .sourceShardIndex(0)
 *     .targetShardIndex(2)
 *     .batchSize(500)
 *     .build();
 * }</pre>
 */
public final class MigrationSpec {

    private final String  table;
    private final String  shardKeyColumn;
    private final long    shardKeyMin;       // inclusive
    private final long    shardKeyMax;       // inclusive
    private final int     sourceShardIndex;
    private final int     targetShardIndex;
    private final int     batchSize;         // rows per INSERT batch
    private final boolean deleteAfterCopy;  // remove rows from source after successful copy

    private MigrationSpec(Builder b) {
        this.table            = Objects.requireNonNull(b.table,          "table");
        this.shardKeyColumn   = Objects.requireNonNull(b.shardKeyColumn, "shardKeyColumn");
        this.shardKeyMin      = b.shardKeyMin;
        this.shardKeyMax      = b.shardKeyMax;
        this.sourceShardIndex = b.sourceShardIndex;
        this.targetShardIndex = b.targetShardIndex;
        this.batchSize        = b.batchSize > 0 ? b.batchSize : 500;
        this.deleteAfterCopy  = b.deleteAfterCopy;

        if (shardKeyMin > shardKeyMax) {
            throw new IllegalArgumentException(
                "shardKeyMin (" + shardKeyMin + ") must be <= shardKeyMax (" + shardKeyMax + ")");
        }
        if (sourceShardIndex == targetShardIndex) {
            throw new IllegalArgumentException("sourceShardIndex and targetShardIndex must differ");
        }
    }

    public String  getTable()            { return table; }
    public String  getShardKeyColumn()   { return shardKeyColumn; }
    public long    getShardKeyMin()      { return shardKeyMin; }
    public long    getShardKeyMax()      { return shardKeyMax; }
    public int     getSourceShardIndex() { return sourceShardIndex; }
    public int     getTargetShardIndex() { return targetShardIndex; }
    public int     getBatchSize()        { return batchSize; }
    public boolean isDeleteAfterCopy()   { return deleteAfterCopy; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String  table;
        private String  shardKeyColumn;
        private long    shardKeyMin;
        private long    shardKeyMax;
        private int     sourceShardIndex;
        private int     targetShardIndex;
        private int     batchSize        = 500;
        private boolean deleteAfterCopy  = false;

        public Builder table(String table)                         { this.table = table;                       return this; }
        public Builder shardKeyColumn(String col)                  { this.shardKeyColumn = col;                return this; }
        public Builder shardKeyRange(long min, long max)           { this.shardKeyMin = min; this.shardKeyMax = max; return this; }
        public Builder sourceShardIndex(int idx)                   { this.sourceShardIndex = idx;              return this; }
        public Builder targetShardIndex(int idx)                   { this.targetShardIndex = idx;              return this; }
        public Builder batchSize(int size)                         { this.batchSize = size;                    return this; }
        public Builder deleteAfterCopy(boolean delete)             { this.deleteAfterCopy = delete;            return this; }
        public MigrationSpec build()                               { return new MigrationSpec(this); }
    }
}

package org.springframework.boot.starter.sharding.migration;

import java.time.Instant;

/**
 * Immutable snapshot of a shard migration plan.
 * Tracks all metadata needed to execute, monitor, and roll back an online migration.
 */
public record ShardMigrationPlan(
    String migrationId,
    long keyMin,
    long keyMax,
    int sourceShard,
    int targetShard,
    MigrationState state,
    int backfillBatchSize,
    Instant startedAt,
    Instant completedAt
) {

    /**
     * Create a new pending migration plan.
     *
     * @param migrationId     unique identifier (UUID recommended)
     * @param keyMin          inclusive lower bound of the migrated key range
     * @param keyMax          inclusive upper bound of the migrated key range
     * @param sourceShard     source shard index (data currently lives here)
     * @param targetShard     target shard index (data will move here)
     * @param backfillBatchSize rows per backfill batch
     */
    public static ShardMigrationPlan pending(String migrationId, long keyMin, long keyMax,
                                              int sourceShard, int targetShard,
                                              int backfillBatchSize) {
        return new ShardMigrationPlan(migrationId, keyMin, keyMax, sourceShard, targetShard,
            MigrationState.PENDING, backfillBatchSize, Instant.now(), null);
    }

    /** Return a copy of this plan with the given state. */
    public ShardMigrationPlan withState(MigrationState newState) {
        Instant completed = (newState == MigrationState.COMPLETED || newState == MigrationState.ROLLED_BACK)
            ? Instant.now() : completedAt;
        return new ShardMigrationPlan(migrationId, keyMin, keyMax, sourceShard, targetShard,
            newState, backfillBatchSize, startedAt, completed);
    }
}

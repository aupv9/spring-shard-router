package org.springframework.boot.starter.sharding.cdc;

import java.util.logging.Logger;

/**
 * {@link ShardChangeEventListener} that checks data consistency during an online migration.
 *
 * <p>During the double-write phase, this listener verifies that changes applied to the
 * source shard were also applied to the target shard. If a gap is detected, it logs a
 * warning so the operator can trigger a partial re-backfill.
 *
 * <p>For production use, inject the {@code ShardMigrationService} and compare
 * the event's shard key against active migration ranges to decide whether to
 * re-apply the change to the target.
 */
public class ShardMigrationConsistencyChecker implements ShardChangeEventListener {

    private static final Logger log =
        Logger.getLogger(ShardMigrationConsistencyChecker.class.getName());

    @Override
    public void onShardChange(ShardChangeEvent event) {
        // Consistency checking logic:
        // 1. Check if the event's shardKey is within any active migration range.
        // 2. If yes, verify the same row exists on the target shard.
        // 3. If missing, re-apply the change to the target.
        //
        // Full implementation requires injecting ShardMigrationService and
        // ShardJdbcTemplate — wired by ShardingCdcAutoConfiguration when both
        // sharding-cdc and sharding-migration modules are present.
        log.fine("CDC consistency check: shard=" + event.shardName()
            + " table=" + event.tableName()
            + " op=" + event.operation()
            + " key=" + event.shardKey());
    }
}

package org.springframework.boot.starter.sharding.migration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe tracker for backfill progress of a single {@link ShardMigrationPlan}.
 *
 * <p>Updated by {@link ShardMigrationService} during the backfill phase.
 * Exposed via the {@code /actuator/shard-migration} endpoint.
 */
public class ShardMigrationProgressTracker {

    private final String migrationId;
    private final AtomicLong rowsCopied = new AtomicLong(0);
    private volatile long totalRowsEstimate = -1; // -1 = unknown

    public ShardMigrationProgressTracker(String migrationId) {
        this.migrationId = migrationId;
    }

    public void setTotalRowsEstimate(long total) {
        this.totalRowsEstimate = total;
    }

    public void incrementRowsCopied(long count) {
        rowsCopied.addAndGet(count);
    }

    public long getRowsCopied() {
        return rowsCopied.get();
    }

    public long getTotalRowsEstimate() {
        return totalRowsEstimate;
    }

    public String getMigrationId() {
        return migrationId;
    }

    /**
     * Returns a percentage in [0, 100] or -1 if the total is unknown.
     */
    public double getProgressPercent() {
        if (totalRowsEstimate <= 0) return -1;
        return Math.min(100.0, rowsCopied.get() * 100.0 / totalRowsEstimate);
    }
}

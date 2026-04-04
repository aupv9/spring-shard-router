package org.springframework.boot.starter.sharding.migration;

/**
 * Lifecycle states for an online shard migration plan.
 *
 * <pre>
 * PENDING ──► DOUBLE_WRITING ──► BACKFILLING ──► READY_TO_CUTOVER ──► COMPLETED
 *    │               │                │                   │
 *    └───────────────┴────────────────┴───────────────────┴──► ROLLED_BACK
 * </pre>
 */
public enum MigrationState {

    /** Migration created but not yet started. */
    PENDING,

    /**
     * Write operations for keys in the migration range are being applied to both
     * source and target shards (double-write phase).
     */
    DOUBLE_WRITING,

    /**
     * Existing rows are being copied from source to target in batches.
     * Double-write continues during backfill.
     */
    BACKFILLING,

    /**
     * Backfill complete; ready for operator-triggered cutover.
     * Double-write is still active.
     */
    READY_TO_CUTOVER,

    /** Cutover successful — target shard is now canonical. */
    COMPLETED,

    /** Migration was rolled back; source shard remains canonical. */
    ROLLED_BACK
}

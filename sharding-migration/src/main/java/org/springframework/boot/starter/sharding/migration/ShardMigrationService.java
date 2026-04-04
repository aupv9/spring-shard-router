package org.springframework.boot.starter.sharding.migration;

import org.springframework.boot.starter.sharding.core.ConsistentHashShardRouter;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.jdbc.ShardJdbcTemplate;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates online shard migrations in three phases:
 *
 * <ol>
 *   <li><b>Double-Write</b> — writes for keys in [keyMin, keyMax] go to both source and target.
 *       Activated by {@link ShardMigrationAspect} when this service registers an active plan.</li>
 *   <li><b>Backfill</b> — existing rows are copied from source to target in batches.
 *       Uses UPSERT semantics so it is safe to re-run.</li>
 *   <li><b>Cutover</b> — router overrides are added for [keyMin, keyMax] → target.
 *       Double-write stops. Source data can be cleaned up lazily.</li>
 * </ol>
 *
 * <p>All state is in-memory. For multi-node deployments, store plans in a shared store
 * (e.g. Redis or a dedicated DB table) — this is left as a future enhancement.
 *
 * <p>Enable via {@code sharding.migration.enabled=true}.
 */
public class ShardMigrationService {

    private final ShardRouter shardRouter;
    private final ShardJdbcTemplate shardJdbcTemplate;

    /** Active and completed migration plans keyed by migrationId. */
    private final Map<String, ShardMigrationPlan> plans = new ConcurrentHashMap<>();
    private final Map<String, ShardMigrationProgressTracker> trackers = new ConcurrentHashMap<>();

    public ShardMigrationService(ShardRouter shardRouter, ShardJdbcTemplate shardJdbcTemplate) {
        this.shardRouter = shardRouter;
        this.shardJdbcTemplate = shardJdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // Plan lifecycle
    // -------------------------------------------------------------------------

    /**
     * Create and start double-write for the given key range.
     *
     * @param keyMin           inclusive lower bound of the migrated key range
     * @param keyMax           inclusive upper bound
     * @param sourceShard      source shard index
     * @param targetShard      target shard index
     * @param backfillBatchSize rows per backfill batch
     * @return the new migration plan
     */
    public ShardMigrationPlan startMigration(long keyMin, long keyMax,
                                              int sourceShard, int targetShard,
                                              int backfillBatchSize) {
        validateShards(sourceShard, targetShard);
        String id = UUID.randomUUID().toString();
        ShardMigrationPlan plan = ShardMigrationPlan.pending(id, keyMin, keyMax,
            sourceShard, targetShard, backfillBatchSize);
        plan = plan.withState(MigrationState.DOUBLE_WRITING);
        plans.put(id, plan);
        trackers.put(id, new ShardMigrationProgressTracker(id));
        return plan;
    }

    /**
     * Run the backfill phase for a migration in DOUBLE_WRITING state.
     *
     * <p>Callers provide the table name and the column used as the shard key so
     * this service can issue range-bounded {@code SELECT … WHERE shard_col BETWEEN ? AND ?}.
     * The copy uses INSERT … ON CONFLICT DO UPDATE (upsert) semantics.
     *
     * @param migrationId migration identifier
     * @param tableName   table to copy
     * @param shardColumn column holding the shard key value
     * @param primaryKey  primary key column name (used for upsert)
     */
    public void runBackfill(String migrationId, String tableName,
                             String shardColumn, String primaryKey) {
        ShardMigrationPlan plan = requirePlan(migrationId, MigrationState.DOUBLE_WRITING);
        plan = plan.withState(MigrationState.BACKFILLING);
        plans.put(migrationId, plan);

        ShardMigrationProgressTracker tracker = trackers.get(migrationId);
        long offset = 0;
        int batchSize = plan.backfillBatchSize();
        boolean hasMore = true;

        while (hasMore) {
            String selectSql = "SELECT * FROM " + tableName
                + " WHERE " + shardColumn + " BETWEEN ? AND ?"
                + " ORDER BY " + primaryKey
                + " LIMIT ? OFFSET ?";

            // Copy rows from source to target via application memory (simplest approach)
            var rows = shardJdbcTemplate.queryForList(plan.sourceShard(), selectSql,
                plan.keyMin(), plan.keyMax(), batchSize, offset);

            if (rows.isEmpty()) {
                hasMore = false;
            } else {
                for (var row : rows) {
                    upsertRow(plan.targetShard(), tableName, primaryKey, row);
                }
                tracker.incrementRowsCopied(rows.size());
                offset += rows.size();
                if (rows.size() < batchSize) {
                    hasMore = false;
                }
            }
        }

        plan = plan.withState(MigrationState.READY_TO_CUTOVER);
        plans.put(migrationId, plan);
    }

    /**
     * Execute cutover: add router overrides for the migrated key range and mark done.
     *
     * @param migrationId migration identifier
     */
    public ShardMigrationPlan cutover(String migrationId) {
        ShardMigrationPlan plan = requirePlan(migrationId, MigrationState.READY_TO_CUTOVER);

        if (shardRouter instanceof ConsistentHashShardRouter chr) {
            for (long key = plan.keyMin(); key <= plan.keyMax(); key++) {
                chr.addOverride(key, plan.targetShard());
            }
        }

        plan = plan.withState(MigrationState.COMPLETED);
        plans.put(migrationId, plan);
        return plan;
    }

    /**
     * Roll back a migration: remove any overrides and stop double-writing.
     *
     * @param migrationId migration identifier
     */
    public ShardMigrationPlan rollback(String migrationId) {
        ShardMigrationPlan plan = plans.get(migrationId);
        if (plan == null) throw new IllegalArgumentException("Unknown migration: " + migrationId);

        if (plan.state() == MigrationState.COMPLETED) {
            // Remove overrides so source shard is canonical again
            if (shardRouter instanceof ConsistentHashShardRouter chr) {
                for (long key = plan.keyMin(); key <= plan.keyMax(); key++) {
                    chr.removeOverride(key);
                }
            }
        }

        plan = plan.withState(MigrationState.ROLLED_BACK);
        plans.put(migrationId, plan);
        return plan;
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public ShardMigrationPlan getPlan(String migrationId) {
        return plans.get(migrationId);
    }

    public Collection<ShardMigrationPlan> listPlans() {
        return plans.values();
    }

    public ShardMigrationProgressTracker getProgress(String migrationId) {
        return trackers.get(migrationId);
    }

    /**
     * Returns {@code true} if {@code shardKey} falls within any active migration range,
     * used by {@link ShardMigrationAspect} to decide whether to double-write.
     */
    public boolean isInActiveMigration(long shardKey) {
        for (ShardMigrationPlan plan : plans.values()) {
            if ((plan.state() == MigrationState.DOUBLE_WRITING
                 || plan.state() == MigrationState.BACKFILLING)
                && shardKey >= plan.keyMin() && shardKey <= plan.keyMax()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find the active migration plan for a given shard key, or {@code null} if none.
     */
    public ShardMigrationPlan findActivePlanFor(long shardKey) {
        for (ShardMigrationPlan plan : plans.values()) {
            if ((plan.state() == MigrationState.DOUBLE_WRITING
                 || plan.state() == MigrationState.BACKFILLING)
                && shardKey >= plan.keyMin() && shardKey <= plan.keyMax()) {
                return plan;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ShardMigrationPlan requirePlan(String id, MigrationState expected) {
        ShardMigrationPlan plan = plans.get(id);
        if (plan == null) throw new IllegalArgumentException("Unknown migration: " + id);
        if (plan.state() != expected) {
            throw new IllegalStateException(
                "Migration " + id + " must be in state " + expected + " but is " + plan.state());
        }
        return plan;
    }

    private void validateShards(int source, int target) {
        int count = shardRouter.getShardCount();
        if (source < 0 || source >= count)
            throw new IllegalArgumentException("Invalid source shard: " + source);
        if (target < 0 || target >= count)
            throw new IllegalArgumentException("Invalid target shard: " + target);
        if (source == target)
            throw new IllegalArgumentException("Source and target shards must differ");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void upsertRow(int targetShard, String table, String pkColumn, Map<String, Object> row) {
        if (row.isEmpty()) return;
        String columns = String.join(", ", row.keySet());
        String placeholders = row.keySet().stream().map(k -> "?").collect(
            java.util.stream.Collectors.joining(", "));
        // PostgreSQL upsert — adjust for other databases as needed
        String sql = "INSERT INTO " + table + " (" + columns + ")"
            + " VALUES (" + placeholders + ")"
            + " ON CONFLICT (" + pkColumn + ") DO UPDATE SET "
            + row.keySet().stream()
                .filter(k -> !k.equals(pkColumn))
                .map(k -> k + " = EXCLUDED." + k)
                .collect(java.util.stream.Collectors.joining(", "));
        shardJdbcTemplate.update(targetShard, sql, row.values().toArray());
    }
}

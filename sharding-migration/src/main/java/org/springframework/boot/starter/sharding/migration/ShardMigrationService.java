package org.springframework.boot.starter.sharding.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Online shard data migration service.
 *
 * <h2>Two-level API</h2>
 * <p>This service exposes two complementary APIs:
 *
 * <h3>1. Low-level: migrate / verify / migrateAndVerify</h3>
 * <p>Direct data-copy operations used for backfill. Stateless — each call
 * is independent.
 *
 * <h3>2. High-level: state-machine lifecycle</h3>
 * <p>Tracks migration plans through their lifecycle
 * ({@link MigrationState#PENDING} → {@link MigrationState#DOUBLE_WRITING} →
 * {@link MigrationState#BACKFILLING} → {@link MigrationState#READY_TO_CUTOVER} →
 * {@link MigrationState#COMPLETED} | {@link MigrationState#ROLLED_BACK}).
 * Used by {@link ShardMigrationActuatorEndpoint} and {@link ShardMigrationAspect}.
 *
 * <h2>Recommended workflow</h2>
 * <ol>
 *   <li>{@link #startMigration} — creates a plan and enters DOUBLE_WRITING state.</li>
 *   <li>Application writes to both source and target during DOUBLE_WRITING
 *       (via {@link ShardMigrationAspect} or manual fan-out).</li>
 *   <li>Call {@link #migrate(MigrationSpec)} to backfill existing rows.</li>
 *   <li>Call {@link #verify(MigrationSpec)} to confirm row counts match.</li>
 *   <li>{@link #cutover} — flips the plan to COMPLETED.</li>
 *   <li>Operator updates {@code sharding.overrides} or router to target shard.</li>
 * </ol>
 */
public class ShardMigrationService {

    private static final Logger log = LoggerFactory.getLogger(ShardMigrationService.class);

    private final ShardRouter shardRouter;

    /** In-memory plan registry. Keyed by migrationId. */
    private final Map<String, ShardMigrationPlan>             plans    = new ConcurrentHashMap<>();
    private final Map<String, ShardMigrationProgressTracker>  trackers = new ConcurrentHashMap<>();

    public ShardMigrationService(ShardRouter shardRouter) {
        this.shardRouter = shardRouter;
    }

    // =========================================================================
    // State-machine lifecycle API
    // =========================================================================

    /**
     * Create a new migration plan and enter the DOUBLE_WRITING state.
     *
     * @param keyMin            inclusive lower bound of the migrated key range
     * @param keyMax            inclusive upper bound of the migrated key range
     * @param sourceShard       index of the source shard
     * @param targetShard       index of the target shard
     * @param backfillBatchSize rows per backfill batch
     * @return the created plan
     */
    public ShardMigrationPlan startMigration(long keyMin, long keyMax,
                                              int sourceShard, int targetShard,
                                              int backfillBatchSize) {
        validateShardIndices(sourceShard, targetShard);
        String id = UUID.randomUUID().toString();
        ShardMigrationPlan plan = ShardMigrationPlan.pending(id, keyMin, keyMax,
            sourceShard, targetShard, backfillBatchSize)
            .withState(MigrationState.DOUBLE_WRITING);

        plans.put(id, plan);
        trackers.put(id, new ShardMigrationProgressTracker(id));
        log.info("[migration] started: id={} key=[{},{}] src={} dst={}", id, keyMin, keyMax, sourceShard, targetShard);
        return plan;
    }

    /**
     * Advance a plan from DOUBLE_WRITING/BACKFILLING to READY_TO_CUTOVER.
     * Called by {@link ShardMigrationActuatorEndpoint} after backfill verification.
     */
    public ShardMigrationPlan markReadyToCutover(String migrationId) {
        ShardMigrationPlan plan = requirePlan(migrationId,
            MigrationState.DOUBLE_WRITING, MigrationState.BACKFILLING);
        return updatePlan(migrationId, plan.withState(MigrationState.READY_TO_CUTOVER));
    }

    /**
     * Complete the migration — transition to COMPLETED.
     * Typically called after the operator has updated routing to use the target shard.
     */
    public ShardMigrationPlan cutover(String migrationId) {
        ShardMigrationPlan plan = requirePlan(migrationId, MigrationState.READY_TO_CUTOVER);
        ShardMigrationPlan completed = updatePlan(migrationId, plan.withState(MigrationState.COMPLETED));
        log.info("[migration] cutover complete: id={}", migrationId);
        return completed;
    }

    /**
     * Roll back the migration — transition to ROLLED_BACK.
     * Can be called from any non-terminal state.
     */
    public ShardMigrationPlan rollback(String migrationId) {
        ShardMigrationPlan plan = requirePlan(migrationId,
            MigrationState.PENDING, MigrationState.DOUBLE_WRITING,
            MigrationState.BACKFILLING, MigrationState.READY_TO_CUTOVER);
        ShardMigrationPlan rolled = updatePlan(migrationId, plan.withState(MigrationState.ROLLED_BACK));
        log.info("[migration] rolled back: id={}", migrationId);
        return rolled;
    }

    /**
     * Look up a plan by ID. Returns {@code null} if not found.
     */
    public ShardMigrationPlan getPlan(String migrationId) {
        return plans.get(migrationId);
    }

    /**
     * List all known migration plans.
     */
    public List<ShardMigrationPlan> listPlans() {
        return List.copyOf(plans.values());
    }

    /**
     * Get the progress tracker for a plan. Returns {@code null} if not found.
     */
    public ShardMigrationProgressTracker getProgress(String migrationId) {
        return trackers.get(migrationId);
    }

    /**
     * Returns {@code true} if the given shard key falls within the range of any
     * active (non-terminal) migration plan.
     */
    public boolean isInActiveMigration(long shardKey) {
        return findActivePlanFor(shardKey) != null;
    }

    /**
     * Find the first active migration plan whose key range covers {@code shardKey}.
     * Returns {@code null} if no match.
     */
    public ShardMigrationPlan findActivePlanFor(long shardKey) {
        for (ShardMigrationPlan plan : plans.values()) {
            if (!isTerminal(plan.state())
                    && shardKey >= plan.keyMin()
                    && shardKey <= plan.keyMax()) {
                return plan;
            }
        }
        return null;
    }

    // =========================================================================
    // Low-level data-copy API
    // =========================================================================

    /**
     * Copy rows for the shard key range described in {@code spec} from source shard
     * to target shard.
     */
    public MigrationResult migrate(MigrationSpec spec) {
        validateSpec(spec);
        Instant started = Instant.now();
        List<String> errors = new ArrayList<>();

        JdbcTemplate source = templateFor(shardRouter.getShard(spec.getSourceShardIndex()));
        JdbcTemplate target = templateFor(shardRouter.getShard(spec.getTargetShardIndex()));

        log.info("[migration] backfill starting: table={} key=[{},{}] src={} dst={} batch={}",
            spec.getTable(), spec.getShardKeyMin(), spec.getShardKeyMax(),
            spec.getSourceShardIndex(), spec.getTargetShardIndex(), spec.getBatchSize());

        long offset = 0, totalCopied = 0, totalDeleted = 0;
        boolean hadError = false;

        while (true) {
            List<Map<String, Object>> rows = source.queryForList(
                buildSelectSql(spec, offset), spec.getShardKeyMin(), spec.getShardKeyMax());

            if (rows.isEmpty()) break;

            try {
                long copied = insertBatch(target, spec.getTable(), rows);
                totalCopied += copied;
                log.debug("[migration] batch copied: offset={} rows={}", offset, copied);
            } catch (Exception ex) {
                String msg = "Batch copy failed at offset " + offset + ": " + ex.getMessage();
                log.error("[migration] {}", msg, ex);
                errors.add(msg);
                hadError = true;
                break;
            }

            if (spec.isDeleteAfterCopy()) {
                try {
                    totalDeleted += deleteBatch(source, spec, rows);
                } catch (Exception ex) {
                    String msg = "Batch delete failed at offset " + offset + ": " + ex.getMessage();
                    log.error("[migration] {}", msg, ex);
                    errors.add(msg);
                    hadError = true;
                    break;
                }
            }

            offset += rows.size();
            if (rows.size() < spec.getBatchSize()) break;
        }

        MigrationResult result = MigrationResult.builder(started)
            .status(hadError ? MigrationResult.Status.PARTIAL_FAILURE : MigrationResult.Status.SUCCESS)
            .rowsCopied(totalCopied)
            .rowsDeleted(totalDeleted)
            .completedAt(Instant.now())
            .errors(errors)
            .build();

        log.info("[migration] backfill finished: {}", result);
        return result;
    }

    /**
     * Verify row counts match between source and target for the given spec.
     */
    public MigrationResult verify(MigrationSpec spec) {
        validateSpec(spec);
        Instant started = Instant.now();

        JdbcTemplate source = templateFor(shardRouter.getShard(spec.getSourceShardIndex()));
        JdbcTemplate target = templateFor(shardRouter.getShard(spec.getTargetShardIndex()));

        String countSql = "SELECT COUNT(*) FROM " + spec.getTable()
            + " WHERE " + spec.getShardKeyColumn() + " BETWEEN ? AND ?";

        long src = orZero(source.queryForObject(countSql, Long.class,
            spec.getShardKeyMin(), spec.getShardKeyMax()));
        long tgt = orZero(target.queryForObject(countSql, Long.class,
            spec.getShardKeyMin(), spec.getShardKeyMax()));

        log.info("[migration] verify: table={} src={} tgt={}", spec.getTable(), src, tgt);

        boolean ok = src == tgt;
        return MigrationResult.builder(started)
            .status(ok ? MigrationResult.Status.SUCCESS : MigrationResult.Status.VERIFICATION_FAILED)
            .rowsVerified(tgt)
            .completedAt(Instant.now())
            .errors(ok ? List.of() : List.of("Row count mismatch: source=" + src + ", target=" + tgt))
            .build();
    }

    /** Migrate then verify. Returns migration result if migration fails. */
    public MigrationResult migrateAndVerify(MigrationSpec spec) {
        MigrationResult r = migrate(spec);
        if (!r.isSuccess()) {
            log.warn("[migration] skipping verify — migration reported: {}", r.getStatus());
            return r;
        }
        return verify(spec);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void validateShardIndices(int source, int target) {
        if (source == target) {
            throw new IllegalArgumentException("sourceShardIndex and targetShardIndex must differ");
        }
        int count = shardRouter.getShardCount();
        if (source < 0 || source >= count) {
            throw new IllegalArgumentException("sourceShardIndex " + source + " out of range (shardCount=" + count + ")");
        }
        if (target < 0 || target >= count) {
            throw new IllegalArgumentException("targetShardIndex " + target + " out of range (shardCount=" + count + ")");
        }
    }

    private void validateSpec(MigrationSpec spec) {
        validateShardIndices(spec.getSourceShardIndex(), spec.getTargetShardIndex());
    }

    private ShardMigrationPlan requirePlan(String migrationId, MigrationState... allowedStates) {
        ShardMigrationPlan plan = plans.get(migrationId);
        if (plan == null) {
            throw new IllegalArgumentException("No migration plan found with id: " + migrationId);
        }
        for (MigrationState allowed : allowedStates) {
            if (plan.state() == allowed) return plan;
        }
        throw new IllegalStateException("Migration " + migrationId + " must be in state "
            + List.of(allowedStates) + " but was " + plan.state());
    }

    private ShardMigrationPlan updatePlan(String migrationId, ShardMigrationPlan updated) {
        plans.put(migrationId, updated);
        return updated;
    }

    private static boolean isTerminal(MigrationState state) {
        return state == MigrationState.COMPLETED || state == MigrationState.ROLLED_BACK;
    }

    private String buildSelectSql(MigrationSpec spec, long offset) {
        return "SELECT * FROM " + spec.getTable()
            + " WHERE " + spec.getShardKeyColumn() + " BETWEEN ? AND ?"
            + " ORDER BY " + spec.getShardKeyColumn()
            + " LIMIT " + spec.getBatchSize()
            + " OFFSET " + offset;
    }

    private long insertBatch(JdbcTemplate target, String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return 0;
        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        String columnList  = String.join(", ", columns);
        String placeholders = "(" + "?,".repeat(columns.size() - 1) + "?)";
        String insertSql = "INSERT INTO " + table + " (" + columnList + ") VALUES "
            + placeholders + " ON CONFLICT DO NOTHING";
        List<Object[]> batchArgs = rows.stream()
            .map(row -> columns.stream().map(row::get).toArray())
            .toList();
        int[] counts = target.batchUpdate(insertSql, batchArgs);
        long total = 0;
        for (int c : counts) total += Math.max(c, 0);
        return total;
    }

    private long deleteBatch(JdbcTemplate source, MigrationSpec spec, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return 0;
        List<Object> keys = rows.stream().map(r -> r.get(spec.getShardKeyColumn())).toList();
        String inClause = "?,".repeat(keys.size() - 1) + "?";
        return source.update(
            "DELETE FROM " + spec.getTable() + " WHERE " + spec.getShardKeyColumn() + " IN (" + inClause + ")",
            keys.toArray());
    }

    private JdbcTemplate templateFor(Shard shard) {
        return new JdbcTemplate(shard.dataSource());
    }

    private static long orZero(Long value) {
        return value != null ? value : 0L;
    }
}

package org.springframework.boot.starter.sharding.migration;

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring Boot Actuator endpoint for shard migration management.
 *
 * <p>Exposed at {@code /actuator/shard-migration} when
 * {@code sharding.migration.enabled=true} and {@code spring-boot-starter-actuator}
 * is on the classpath.
 *
 * <p><b>Security note:</b> these are write endpoints. Protect them with Spring Security
 * and limit exposure via {@code management.endpoints.web.exposure.include}.
 */
@Endpoint(id = "shard-migration")
public class ShardMigrationActuatorEndpoint {

    private final ShardMigrationService migrationService;

    public ShardMigrationActuatorEndpoint(ShardMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /**
     * List all migration plans.
     * Maps to {@code GET /actuator/shard-migration}.
     */
    @ReadOperation
    public List<Map<String, Object>> listMigrations() {
        return migrationService.listPlans().stream()
            .map(this::toMap)
            .collect(Collectors.toList());
    }

    /**
     * Start a new migration.
     * Maps to {@code POST /actuator/shard-migration}.
     */
    @WriteOperation
    public Map<String, Object> startMigration(long keyMin, long keyMax,
                                               int sourceShard, int targetShard,
                                               int backfillBatchSize) {
        ShardMigrationPlan plan = migrationService.startMigration(
            keyMin, keyMax, sourceShard, targetShard, backfillBatchSize);
        return toMap(plan);
    }

    /**
     * Trigger cutover or rollback for a given migration.
     * Maps to {@code DELETE /actuator/shard-migration/{migrationId}}.
     *
     * @param migrationId the migration to act on
     * @param action      {@code "cutover"} or {@code "rollback"}
     */
    @DeleteOperation
    public Map<String, Object> actOnMigration(@Selector String migrationId, String action) {
        ShardMigrationPlan result = switch (action.toLowerCase()) {
            case "cutover"  -> migrationService.cutover(migrationId);
            case "rollback" -> migrationService.rollback(migrationId);
            default         -> throw new IllegalArgumentException("Unknown action: " + action +
                ". Use 'cutover' or 'rollback'.");
        };
        return toMap(result);
    }

    private Map<String, Object> toMap(ShardMigrationPlan plan) {
        ShardMigrationProgressTracker tracker = migrationService.getProgress(plan.migrationId());
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("migrationId", plan.migrationId());
        map.put("keyMin", plan.keyMin());
        map.put("keyMax", plan.keyMax());
        map.put("sourceShard", plan.sourceShard());
        map.put("targetShard", plan.targetShard());
        map.put("state", plan.state().name());
        map.put("startedAt", plan.startedAt() != null ? plan.startedAt().toString() : null);
        map.put("completedAt", plan.completedAt() != null ? plan.completedAt().toString() : null);
        if (tracker != null) {
            map.put("rowsCopied", tracker.getRowsCopied());
            map.put("totalRowsEstimate", tracker.getTotalRowsEstimate());
            map.put("progressPercent", tracker.getProgressPercent());
        }
        return map;
    }
}

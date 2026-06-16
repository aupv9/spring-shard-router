package org.springframework.boot.starter.sharding.metrics;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardRouter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot Actuator {@link HealthIndicator} that probes every configured shard
 * with a lightweight {@code SELECT 1} and reports per-shard UP/DOWN detail.
 *
 * <p>Registered automatically as the {@code "shards"} health indicator when
 * {@code sharding.enabled=true} and {@code spring-boot-starter-actuator} is on the
 * classpath. Visible at {@code /actuator/health/shards}.
 *
 * <h2>Status rules</h2>
 * <table border="1" cellpadding="4">
 *   <tr><th>Condition</th><th>Composite status</th></tr>
 *   <tr><td>All shards UP</td><td>{@code UP}</td></tr>
 *   <tr><td>Some shards DOWN</td><td>{@code DEGRADED} (custom status)</td></tr>
 *   <tr><td>All shards DOWN</td><td>{@code DOWN}</td></tr>
 * </table>
 *
 * <p>{@code DEGRADED} means the application can still serve requests whose shard key
 * lands on a healthy shard, but operations targeting a down shard will fail. This
 * intentional distinction lets operators configure their load-balancer to keep
 * sending traffic while alerting on partial failure.
 *
 * <h2>Read replica awareness</h2>
 * <p>Only primary DataSources are probed. Read replicas are not probed here —
 * they are covered by the standard Spring Boot DataSource health indicator if
 * exposed separately.
 *
 * <h2>Connection management</h2>
 * <p>Each probe borrows a connection from the shard's HikariCP pool, executes
 * {@code SELECT 1}, and immediately returns it. The probe times out after
 * {@link #PROBE_TIMEOUT_SECONDS} seconds so a hung shard cannot block the
 * Actuator endpoint indefinitely.
 */
public class ShardHealthIndicator implements HealthIndicator {

    /** Custom status string used when some (but not all) shards are DOWN. */
    public static final String STATUS_DEGRADED = "DEGRADED";

    /** Seconds before a probe connection attempt is considered failed. */
    private static final int PROBE_TIMEOUT_SECONDS = 3;

    private final ShardRouter shardRouter;

    public ShardHealthIndicator(ShardRouter shardRouter) {
        this.shardRouter = shardRouter;
    }

    @Override
    public Health health() {
        int total = shardRouter.getShardCount();
        int downCount = 0;

        // LinkedHashMap preserves shard-0, shard-1, ... ordering in the response
        Map<String, Object> details = new LinkedHashMap<>();

        for (int i = 0; i < total; i++) {
            Shard shard = shardRouter.getShard(i);
            ShardProbeResult result = probe(shard.dataSource());

            if (result.healthy()) {
                details.put(shard.name(), Map.of(
                    "status", "UP",
                    "responseTimeMs", result.responseTimeMs()
                ));
            } else {
                downCount++;
                details.put(shard.name(), Map.of(
                    "status", "DOWN",
                    "error", result.errorMessage()
                ));
            }
        }

        details.put("shardCount", total);
        details.put("downCount", downCount);

        if (downCount == 0) {
            return Health.up().withDetails(details).build();
        }
        if (downCount == total) {
            return Health.down().withDetails(details).build();
        }
        // Partial failure — custom DEGRADED status
        return Health.status(STATUS_DEGRADED).withDetails(details).build();
    }

    // -------------------------------------------------------------------------
    // Probe logic
    // -------------------------------------------------------------------------

    private ShardProbeResult probe(DataSource dataSource) {
        long start = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            connection.setNetworkTimeout(
                Runnable::run,                     // inline executor — no thread switch needed
                PROBE_TIMEOUT_SECONDS * 1_000
            );
            try (PreparedStatement ps = connection.prepareStatement("SELECT 1")) {
                ps.execute();
            }
            long elapsed = System.currentTimeMillis() - start;
            return ShardProbeResult.healthy(elapsed);
        } catch (Exception ex) {
            return ShardProbeResult.unhealthy(ex.getMessage() != null
                ? ex.getMessage()
                : ex.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // Value type for probe outcome
    // -------------------------------------------------------------------------

    private record ShardProbeResult(boolean healthy, long responseTimeMs, String errorMessage) {

        static ShardProbeResult healthy(long responseTimeMs) {
            return new ShardProbeResult(true, responseTimeMs, null);
        }

        static ShardProbeResult unhealthy(String errorMessage) {
            return new ShardProbeResult(false, -1, errorMessage);
        }
    }
}

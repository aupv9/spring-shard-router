package org.springframework.boot.starter.sharding.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.metrics.MeteredShardJdbcTemplate;
import org.springframework.boot.starter.sharding.metrics.MeteredShardRouter;
import org.springframework.boot.starter.sharding.metrics.ShardActuatorMetrics;
import org.springframework.boot.starter.sharding.metrics.ShardHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Auto-configuration for Micrometer-based shard metrics and Actuator health.
 *
 * <p>Activates only when:
 * <ul>
 *   <li>{@code sharding.enabled=true}</li>
 *   <li>Micrometer ({@code MeterRegistry}) is present on the classpath and as a bean</li>
 *   <li>{@code sharding-metrics} module is present on the classpath</li>
 * </ul>
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link MeteredShardRouter} — wraps the raw router with routing counters + latency timers.
 *       Declared {@code @Primary} so every injection point that asks for {@link ShardRouter}
 *       by type (e.g. {@code ShardScatterGatherTemplate}) gets the metered version.</li>
 *   <li>{@link MeteredShardJdbcTemplate} — wraps the JDBC template with per-operation timers
 *       and error counters.</li>
 *   <li>{@link ShardActuatorMetrics} — live gauge for {@code sharding.shard.count}.
 *       Injected with the raw {@code @Qualifier("shardRouter")} to avoid double-wrapping.</li>
 *   <li>{@link ShardHealthIndicator} — per-shard {@code SELECT 1} probe, registered under
 *       the name {@code "shardsHealthIndicator"} so Actuator exposes it at
 *       {@code /actuator/health/shards}. Requires {@code spring-boot-starter-actuator}.</li>
 * </ul>
 */
@AutoConfiguration(after = ShardingAutoConfiguration.class)
@ConditionalOnProperty(name = "sharding.enabled", havingValue = "true")
@ConditionalOnClass({MeterRegistry.class, MeteredShardRouter.class})
@ConditionalOnBean(MeterRegistry.class)
public class ShardingMetricsAutoConfiguration {

    /**
     * Metered wrapper around the raw {@link ShardRouter}.
     *
     * <p>Declared {@code @Primary} so all injection points that resolve {@link ShardRouter}
     * by type — including {@link org.springframework.boot.starter.sharding.jdbc.ShardScatterGatherTemplate}
     * created in {@link ShardingAutoConfiguration} — receive the instrumented version.
     *
     * <p>Uses {@code @Qualifier("shardRouter")} to inject the raw (un-metered) delegate,
     * preventing a wrapping loop where the metered router wraps itself.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(MeteredShardRouter.class)
    public MeteredShardRouter meteredShardRouter(
            @Qualifier("shardRouter") ShardRouter delegate,
            MeterRegistry registry,
            ShardProperties properties) {
        String strategy = properties.getStrategy().name().toLowerCase();
        return new MeteredShardRouter(delegate, registry, strategy);
    }

    /**
     * Metered wrapper around {@link org.springframework.boot.starter.sharding.jdbc.ShardJdbcTemplate}.
     *
     * <p>Injects the metered router (via {@code MeteredShardRouter} type match) so the
     * shard-name resolution inside the template already uses the instrumented path.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(MeteredShardJdbcTemplate.class)
    public MeteredShardJdbcTemplate meteredShardJdbcTemplate(
            @Qualifier("shardingDataSource") DataSource shardingDataSource,
            MeteredShardRouter meteredShardRouter,
            MeterRegistry registry) {
        return new MeteredShardJdbcTemplate(shardingDataSource, meteredShardRouter, registry);
    }

    /**
     * Live gauge for {@code sharding.shard.count}.
     *
     * <p>Intentionally uses {@code @Qualifier("shardRouter")} (the raw router) rather
     * than injecting by type. Injecting the {@code @Primary} {@link MeteredShardRouter}
     * here would cause {@link MeteredShardRouter#getShardCount()} to be called, which
     * in turn calls the delegate's {@code getShardCount()} — correct behaviour, but the
     * raw router is cheaper and avoids a dependency on metrics infrastructure in the gauge.
     */
    @Bean
    @ConditionalOnMissingBean(ShardActuatorMetrics.class)
    public ShardActuatorMetrics shardActuatorMetrics(
            @Qualifier("shardRouter") ShardRouter shardRouter) {
        return new ShardActuatorMetrics(shardRouter);
    }

    /**
     * Per-shard health indicator — probes every shard's primary DataSource with
     * {@code SELECT 1} and reports individual UP/DOWN status.
     *
     * <p>Visible at {@code /actuator/health/shards}. Uses the raw router (not the metered
     * wrapper) so health checks do not pollute routing latency metrics.
     *
     * <p>Activated only when Spring Boot Actuator ({@code HealthIndicator}) is on
     * the classpath.
     */
    @Bean("shardsHealthIndicator")
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(ShardHealthIndicator.class)
    public ShardHealthIndicator shardsHealthIndicator(
            @Qualifier("shardRouter") ShardRouter shardRouter) {
        return new ShardHealthIndicator(shardRouter);
    }
}

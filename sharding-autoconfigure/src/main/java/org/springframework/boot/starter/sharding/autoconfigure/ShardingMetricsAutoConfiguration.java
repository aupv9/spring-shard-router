package org.springframework.boot.starter.sharding.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.metrics.MeteredShardJdbcTemplate;
import org.springframework.boot.starter.sharding.metrics.MeteredShardRouter;
import org.springframework.boot.starter.sharding.metrics.ShardActuatorMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Auto-configuration for Micrometer-based shard metrics.
 *
 * <p>Activates only when:
 * <ul>
 *   <li>{@code sharding.enabled=true}</li>
 *   <li>Micrometer ({@code MeterRegistry}) is present on the classpath and as a bean</li>
 *   <li>{@code sharding-metrics} module is present on the classpath</li>
 * </ul>
 *
 * <p>Wraps the raw {@link ShardRouter} and {@code ShardJdbcTemplate} with metered
 * decorators. Both decorated beans are {@code @Primary} so injection-by-type picks
 * them up automatically.
 */
@AutoConfiguration(after = ShardingAutoConfiguration.class)
@ConditionalOnProperty(name = "sharding.enabled", havingValue = "true")
@ConditionalOnClass({MeterRegistry.class, MeteredShardRouter.class})
@ConditionalOnBean(MeterRegistry.class)
public class ShardingMetricsAutoConfiguration {

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

    @Bean
    @Primary
    @ConditionalOnMissingBean(MeteredShardJdbcTemplate.class)
    public MeteredShardJdbcTemplate meteredShardJdbcTemplate(
            @Qualifier("shardingDataSource") DataSource shardingDataSource,
            MeteredShardRouter meteredShardRouter,
            MeterRegistry registry) {
        return new MeteredShardJdbcTemplate(shardingDataSource, meteredShardRouter, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ShardActuatorMetrics shardActuatorMetrics(ShardRouter shardRouter) {
        return new ShardActuatorMetrics(shardRouter);
    }
}

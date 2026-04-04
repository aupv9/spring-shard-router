package org.springframework.boot.starter.sharding.cdc;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the CDC (Change Data Capture) integration.
 *
 * <p>Activates when {@code sharding.cdc.enabled=true}.
 *
 * <p>Registers default implementations of built-in listeners
 * ({@link ShardMigrationConsistencyChecker} and {@link ShardCacheInvalidator}).
 * The actual CDC producers ({@link DebeziumShardChangeProducer} or
 * {@link KafkaShardChangeProducer}) must be registered by the application since
 * they require shard-specific connection details.
 *
 * <p>Example minimal configuration:
 * <pre>{@code
 * # application.yml
 * sharding:
 *   cdc:
 *     enabled: true
 *     type: kafka
 *     kafka:
 *       bootstrap-servers: localhost:9092
 *       topic-prefix: sharding.changes
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "sharding.cdc.enabled", havingValue = "true")
public class ShardingCdcAutoConfiguration {

    /**
     * Default consistency checker — logs events. Override by declaring your own
     * {@link ShardMigrationConsistencyChecker} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardMigrationConsistencyChecker shardMigrationConsistencyChecker() {
        return new ShardMigrationConsistencyChecker();
    }

    /**
     * Default cache invalidator — logs events. Override by subclassing
     * {@link ShardCacheInvalidator} and declaring your own bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardCacheInvalidator shardCacheInvalidator() {
        return new ShardCacheInvalidator();
    }
}

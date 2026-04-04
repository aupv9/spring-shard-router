package org.springframework.boot.starter.sharding.migration;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.jdbc.ShardJdbcTemplate;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for online shard migration.
 *
 * <p>Activates when {@code sharding.migration.enabled=true} and the
 * {@code sharding-migration} module is on the classpath.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link ShardMigrationService} — orchestrates migration lifecycle</li>
 *   <li>{@link ShardMigrationAspect} — double-write interceptor
 *       (only when {@code sharding.migration.double-write-enabled=true})</li>
 *   <li>{@link ShardMigrationActuatorEndpoint} — actuator endpoint
 *       (only when {@code spring-boot-starter-actuator} is on the classpath)</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "sharding.migration.enabled", havingValue = "true")
public class ShardingMigrationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ShardMigrationService shardMigrationService(ShardRouter shardRouter,
                                                        ShardJdbcTemplate shardJdbcTemplate) {
        return new ShardMigrationService(shardRouter, shardJdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "sharding.migration.double-write-enabled", havingValue = "true")
    public ShardMigrationAspect shardMigrationAspect(ShardMigrationService migrationService) {
        return new ShardMigrationAspect(migrationService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Endpoint.class)
    public ShardMigrationActuatorEndpoint shardMigrationActuatorEndpoint(
            ShardMigrationService migrationService) {
        return new ShardMigrationActuatorEndpoint(migrationService);
    }
}

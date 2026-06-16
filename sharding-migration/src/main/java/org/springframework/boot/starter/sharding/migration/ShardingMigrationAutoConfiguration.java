package org.springframework.boot.starter.sharding.migration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the shard migration service.
 *
 * <p>Activates when {@code sharding.migration.enabled=true} and the
 * {@code sharding-migration} module is on the classpath.
 *
 * <p>Example YAML:
 * <pre>{@code
 * sharding:
 *   migration:
 *     enabled: true
 * }</pre>
 *
 * <p>Once enabled, inject {@link ShardMigrationService} and call:
 * <pre>{@code
 * MigrationSpec spec = MigrationSpec.builder()
 *     .table("transactions")
 *     .shardKeyColumn("account_id")
 *     .shardKeyRange(1000L, 9999L)
 *     .sourceShardIndex(0)
 *     .targetShardIndex(2)
 *     .batchSize(500)
 *     .deleteAfterCopy(false)
 *     .build();
 *
 * MigrationResult result = migrationService.migrateAndVerify(spec);
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "sharding.migration.enabled", havingValue = "true")
@ConditionalOnClass(ShardMigrationService.class)
public class ShardingMigrationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ShardMigrationService shardMigrationService(ShardRouter shardRouter) {
        return new ShardMigrationService(shardRouter);
    }
}

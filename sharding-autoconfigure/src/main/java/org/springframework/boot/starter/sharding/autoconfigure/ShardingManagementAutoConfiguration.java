package org.springframework.boot.starter.sharding.autoconfigure;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for dynamic shard management.
 *
 * <p>Activates when {@code sharding.management.enabled=true}.
 * Exposes a {@link ShardManagementEndpoint} (Actuator endpoint at {@code /actuator/shards})
 * and a {@link ShardManagementService} for programmatic shard lifecycle management.
 */
@AutoConfiguration(after = ShardingAutoConfiguration.class)
@ConditionalOnProperty(name = "sharding.management.enabled", havingValue = "true")
public class ShardingManagementAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ShardManagementService shardManagementService(
            @Qualifier("shardRouter") ShardRouter shardRouter,
            ShardingAutoConfiguration autoConfig) {
        return new ShardManagementService(shardRouter, autoConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Endpoint.class)
    public ShardManagementEndpoint shardManagementEndpoint(ShardManagementService service) {
        return new ShardManagementEndpoint(service);
    }
}

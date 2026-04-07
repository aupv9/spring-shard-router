package org.springframework.boot.starter.sharding.saga;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for cross-shard Saga orchestration.
 *
 * <p>Activated by {@code sharding.saga.enabled=true}. Registers a single
 * {@link ShardSagaOrchestrator} bean that can be injected anywhere in the application.
 *
 * <p>Typical YAML:
 * <pre>{@code
 * sharding:
 *   saga:
 *     enabled: true
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "sharding.saga.enabled", havingValue = "true")
public class ShardingSagaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ShardSagaOrchestrator shardSagaOrchestrator() {
        return new ShardSagaOrchestrator();
    }
}

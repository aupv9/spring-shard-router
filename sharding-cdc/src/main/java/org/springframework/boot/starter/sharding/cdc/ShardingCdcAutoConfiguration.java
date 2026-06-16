package org.springframework.boot.starter.sharding.cdc;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Auto-configuration for the shard CDC subsystem.
 *
 * <p>Activates when {@code sharding.cdc.enabled=true}. Registers a
 * {@link ShardCdcManager} that collects all {@link ShardCdcSource} and
 * {@link ShardCdcListener} beans from the application context and wires them together.
 *
 * <p>Example YAML:
 * <pre>{@code
 * sharding:
 *   cdc:
 *     enabled: true
 * }</pre>
 *
 * <p>To activate CDC, declare at least one {@link ShardCdcSource} bean and at least one
 * {@link ShardCdcListener} bean in your application context. The manager picks them
 * up automatically via Spring's {@link ObjectProvider}.
 *
 * <p>Minimal example:
 * <pre>{@code
 * // Source — polls shard-0's transactions table every 5 seconds
 * @Bean
 * ShardCdcSource txnSource(ShardRouter router) {
 *     return PollingShardCdcSource.builder()
 *         .shardIndex(0)
 *         .dataSource(router.getShard(0).dataSource())
 *         .table("transactions")
 *         .shardKeyColumn("account_id")
 *         .pollIntervalSeconds(5)
 *         .build();
 * }
 *
 * // Listener — receives every event
 * @Bean
 * ShardCdcListener loggingListener() {
 *     return event -> log.info("CDC event: {}", event);
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "sharding.cdc.enabled", havingValue = "true")
public class ShardingCdcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ShardCdcManager shardCdcManager(
            ObjectProvider<ShardCdcSource>   sourcesProvider,
            ObjectProvider<ShardCdcListener> listenersProvider) {

        List<ShardCdcSource>   sources   = sourcesProvider.orderedStream().collect(Collectors.toList());
        List<ShardCdcListener> listeners = listenersProvider.orderedStream().collect(Collectors.toList());

        return new ShardCdcManager(sources, listeners) {
            @PostConstruct
            public void init()    { start(); }

            @PreDestroy
            public void destroy() { stop(); }
        };
    }
}

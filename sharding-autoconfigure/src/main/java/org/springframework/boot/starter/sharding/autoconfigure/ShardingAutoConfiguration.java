package org.springframework.boot.starter.sharding.autoconfigure;

import org.springframework.boot.starter.sharding.core.ConsistentHashShardRouter;
import org.springframework.boot.starter.sharding.core.HashShardRouter;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.boot.starter.sharding.core.ShardContextTaskDecorator;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.jdbc.DefaultShardingClient;
import org.springframework.boot.starter.sharding.jdbc.RoutingDataSource;
import org.springframework.boot.starter.sharding.jdbc.ShardJdbcTemplate;
import org.springframework.boot.starter.sharding.jdbc.ShardScatterGatherTemplate;
import org.springframework.boot.starter.sharding.jdbc.ShardTransactionManager;
import org.springframework.boot.starter.sharding.jdbc.ShardingClient;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot auto-configuration for sharding
 * Automatically configures sharding components when enabled
 */
@AutoConfiguration
@EnableConfigurationProperties(ShardProperties.class)
@ConditionalOnProperty(name = "sharding.enabled", havingValue = "true")
public class ShardingAutoConfiguration {
    
    /**
     * Create shard router based on configured strategy.
     * Use CONSISTENT_HASH when you plan to add/remove shards in production.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardRouter shardRouter(ShardProperties properties) {
        List<Shard> shards = createShards(properties);
        if (properties.getStrategy() == ShardProperties.Strategy.CONSISTENT_HASH) {
            return new ConsistentHashShardRouter(shards, properties.getOverrides(),
                properties.getVirtualNodesPerShard());
        }
        return new HashShardRouter(shards, properties.getOverrides());
    }
    
    /**
     * Create routing data source wrapped in LazyConnectionDataSourceProxy.
     *
     * The LazyConnectionDataSourceProxy is critical for @Transactional support:
     * Spring's DataSourceTransactionManager calls getConnection() inside doBegin()
     * — BEFORE the @Transactional method body runs. Without the lazy proxy,
     * RoutingDataSource would call ShardContext.get() at that point and find null.
     *
     * With the lazy proxy, the real getConnection() on RoutingDataSource is deferred
     * until the first actual SQL statement, by which time ShardJdbcTemplate has already
     * called ShardContext.set(shardKey). After the first SQL, Spring caches the
     * connection for the transaction so subsequent calls don't need the shard key.
     */
    @Bean
    @ConditionalOnMissingBean(name = "shardingDataSource")
    public DataSource shardingDataSource(ShardRouter shardRouter) {
        return new LazyConnectionDataSourceProxy(new RoutingDataSource(shardRouter));
    }
    
    /**
     * Create shard-aware JDBC template
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardJdbcTemplate shardJdbcTemplate(DataSource shardingDataSource) {
        return new ShardJdbcTemplate(shardingDataSource);
    }
    
    /**
     * Create shard-aware transaction manager
     */
    @Bean
    @ConditionalOnMissingBean(name = "shardTransactionManager")
    public PlatformTransactionManager shardTransactionManager(DataSource shardingDataSource) {
        return new ShardTransactionManager(shardingDataSource);
    }

    /**
     * Scatter-gather template for cross-shard queries.
     * Queries all shards in parallel and merges results.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardScatterGatherTemplate shardScatterGatherTemplate(ShardRouter shardRouter) {
        return new ShardScatterGatherTemplate(shardRouter);
    }

    /**
     * Unified sharding facade — the single recommended entry point for application code.
     * Wraps {@link ShardJdbcTemplate} (single-shard) and {@link ShardScatterGatherTemplate}
     * (fan-out) behind a fluent, discoverable API.
     */
    @Bean
    @ConditionalOnMissingBean(ShardingClient.class)
    public ShardingClient shardingClient(ShardRouter shardRouter,
                                          ShardJdbcTemplate shardJdbcTemplate,
                                          ShardScatterGatherTemplate shardScatterGatherTemplate) {
        return new DefaultShardingClient(shardRouter, shardJdbcTemplate, shardScatterGatherTemplate);
    }

    /**
     * TaskDecorator that propagates shard context into @Async threads.
     * Register this with your ThreadPoolTaskExecutor to enable shard-aware async.
     */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    public ShardContextTaskDecorator shardContextTaskDecorator() {
        return new ShardContextTaskDecorator();
    }

    /**
     * Disables the shard-0 silent fallback once the application is fully started.
     *
     * <p>During startup, {@link RoutingDataSource} falls back to shard-0 when no shard key
     * is set. This is required for Hibernate schema validation and HikariCP pool probing.
     * Once {@link ApplicationReadyEvent} fires, the fallback is disabled so that any
     * missing shard key throws {@link org.springframework.boot.starter.sharding.core.MissingShardKeyException}
     * instead of silently routing to shard-0.
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> shardFallbackDisabler() {
        return event -> ShardContext.disableFallback();
    }
    
    /**
     * Create individual shard data sources and wrap them in Shard objects.
     * If a shard config contains read-replica entries, builds them too.
     */
    private List<Shard> createShards(ShardProperties properties) {
        List<ShardProperties.ShardConfig> shardConfigs = properties.getShards();
        if (shardConfigs.isEmpty()) {
            throw new IllegalArgumentException("At least one shard must be configured");
        }

        List<Shard> shards = new ArrayList<>();
        for (int i = 0; i < shardConfigs.size(); i++) {
            ShardProperties.ShardConfig config = shardConfigs.get(i);
            DataSource primaryDataSource = createDataSource(config.getDatasource(),
                "shard-" + config.getName());

            List<ShardProperties.DataSourceConfig> replicaConfigs = config.getReadReplicas();
            if (replicaConfigs.isEmpty()) {
                shards.add(Shard.of(config.getName(), i, primaryDataSource));
            } else {
                List<DataSource> replicas = new ArrayList<>();
                for (int r = 0; r < replicaConfigs.size(); r++) {
                    replicas.add(createDataSource(replicaConfigs.get(r),
                        "shard-" + config.getName() + "-replica-" + r));
                }
                shards.add(Shard.withReplicas(config.getName(), i, primaryDataSource, replicas));
            }
        }

        return shards;
    }
    
    /**
     * Create HikariCP data source for a shard or replica.
     *
     * @param dsConfig the datasource configuration
     * @param poolName pool name for HikariCP monitoring
     */
    DataSource createDataSource(ShardProperties.DataSourceConfig dsConfig, String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dsConfig.getJdbcUrl());
        config.setUsername(dsConfig.getUsername());
        config.setPassword(dsConfig.getPassword());
        config.setDriverClassName(dsConfig.getDriverClassName());

        // Connection pool settings
        config.setMaximumPoolSize(dsConfig.getMaximumPoolSize());
        config.setMinimumIdle(dsConfig.getMinimumIdle());
        config.setConnectionTimeout(dsConfig.getConnectionTimeout());
        config.setIdleTimeout(dsConfig.getIdleTimeout());
        config.setMaxLifetime(dsConfig.getMaxLifetime());

        // Pool name for monitoring
        config.setPoolName(poolName);

        // Finance-grade settings
        config.setLeakDetectionThreshold(60000); // 1 minute
        config.setConnectionTestQuery("SELECT 1");

        return new HikariDataSource(config);
    }
}
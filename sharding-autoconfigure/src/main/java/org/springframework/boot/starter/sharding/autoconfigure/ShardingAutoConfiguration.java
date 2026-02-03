package org.springframework.boot.starter.sharding.autoconfigure;

import org.springframework.boot.starter.sharding.core.HashShardRouter;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.jdbc.RoutingDataSource;
import org.springframework.boot.starter.sharding.jdbc.ShardJdbcTemplate;
import org.springframework.boot.starter.sharding.jdbc.ShardTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
     * Create shard router based on configuration
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardRouter shardRouter(ShardProperties properties) {
        List<Shard> shards = createShards(properties);
        return new HashShardRouter(shards, properties.getOverrides());
    }
    
    /**
     * Create routing data source
     */
    @Bean
    @ConditionalOnMissingBean(name = "shardingDataSource")
    public DataSource shardingDataSource(ShardRouter shardRouter) {
        return new RoutingDataSource(shardRouter);
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
     * Create individual shard data sources and wrap them in Shard objects
     */
    private List<Shard> createShards(ShardProperties properties) {
        List<ShardProperties.ShardConfig> shardConfigs = properties.getShards();
        if (shardConfigs.isEmpty()) {
            throw new IllegalArgumentException("At least one shard must be configured");
        }
        
        List<Shard> shards = new ArrayList<>();
        for (int i = 0; i < shardConfigs.size(); i++) {
            ShardProperties.ShardConfig config = shardConfigs.get(i);
            DataSource dataSource = createDataSource(config);
            shards.add(Shard.of(config.getName(), i, dataSource));
        }
        
        return shards;
    }
    
    /**
     * Create HikariCP data source for individual shard
     */
    private DataSource createDataSource(ShardProperties.ShardConfig shardConfig) {
        ShardProperties.DataSourceConfig dsConfig = shardConfig.getDatasource();
        
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
        config.setPoolName("shard-" + shardConfig.getName());
        
        // Finance-grade settings
        config.setLeakDetectionThreshold(60000); // 1 minute
        config.setConnectionTestQuery("SELECT 1");
        
        return new HikariDataSource(config);
    }
}
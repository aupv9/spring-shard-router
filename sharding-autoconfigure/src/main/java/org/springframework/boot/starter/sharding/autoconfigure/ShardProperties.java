package org.springframework.boot.starter.sharding.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for sharding
 * Maps to application.yml sharding section
 */
@ConfigurationProperties(prefix = "sharding")
public class ShardProperties {
    
    /**
     * Enable/disable sharding
     */
    private boolean enabled = false;
    
    /**
     * Sharding strategy (currently only HASH supported)
     */
    private Strategy strategy = Strategy.HASH;
    
    /**
     * List of shard configurations
     */
    private List<ShardConfig> shards = new ArrayList<>();
    
    /**
     * Shard key overrides for VIP/migration
     * Map of shard key -> target shard index
     */
    private Map<Long, Integer> overrides = new HashMap<>();
    
    // Getters and setters
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public Strategy getStrategy() {
        return strategy;
    }
    
    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }
    
    public List<ShardConfig> getShards() {
        return shards;
    }
    
    public void setShards(List<ShardConfig> shards) {
        this.shards = shards;
    }
    
    public Map<Long, Integer> getOverrides() {
        return overrides;
    }
    
    public void setOverrides(Map<Long, Integer> overrides) {
        this.overrides = overrides;
    }
    
    /**
     * Sharding strategy enum
     */
    public enum Strategy {
        HASH
    }
    
    /**
     * Individual shard configuration
     */
    public static class ShardConfig {
        
        /**
         * Shard name (for identification)
         */
        private String name;
        
        /**
         * DataSource configuration
         */
        private DataSourceConfig datasource;
        
        // Getters and setters
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public DataSourceConfig getDatasource() {
            return datasource;
        }
        
        public void setDatasource(DataSourceConfig datasource) {
            this.datasource = datasource;
        }
    }
    
    /**
     * DataSource configuration for each shard
     */
    public static class DataSourceConfig {
        
        private String jdbcUrl;
        private String username;
        private String password;
        private String driverClassName = "org.postgresql.Driver";
        
        // HikariCP specific settings
        private int maximumPoolSize = 10;
        private int minimumIdle = 5;
        private long connectionTimeout = 30000;
        private long idleTimeout = 600000;
        private long maxLifetime = 1800000;
        
        // Getters and setters
        
        public String getJdbcUrl() {
            return jdbcUrl;
        }
        
        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }
        
        public String getUsername() {
            return username;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
        
        public String getPassword() {
            return password;
        }
        
        public void setPassword(String password) {
            this.password = password;
        }
        
        public String getDriverClassName() {
            return driverClassName;
        }
        
        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }
        
        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }
        
        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }
        
        public int getMinimumIdle() {
            return minimumIdle;
        }
        
        public void setMinimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
        }
        
        public long getConnectionTimeout() {
            return connectionTimeout;
        }
        
        public void setConnectionTimeout(long connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }
        
        public long getIdleTimeout() {
            return idleTimeout;
        }
        
        public void setIdleTimeout(long idleTimeout) {
            this.idleTimeout = idleTimeout;
        }
        
        public long getMaxLifetime() {
            return maxLifetime;
        }
        
        public void setMaxLifetime(long maxLifetime) {
            this.maxLifetime = maxLifetime;
        }
    }
}
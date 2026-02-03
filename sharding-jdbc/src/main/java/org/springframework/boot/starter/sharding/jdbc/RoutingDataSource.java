package org.springframework.boot.starter.sharding.jdbc;

import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Routing DataSource that delegates to shard-specific DataSource
 * Based on ThreadLocal shard key context
 */
public class RoutingDataSource extends AbstractDataSource {
    
    private final ShardRouter shardRouter;
    
    public RoutingDataSource(ShardRouter shardRouter) {
        this.shardRouter = shardRouter;
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return getTargetDataSource().getConnection();
    }
    
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getTargetDataSource().getConnection(username, password);
    }
    
    /**
     * Resolve target DataSource based on current shard context
     * @return target DataSource for current shard
     * @throws IllegalStateException if shard key is not set
     */
    private javax.sql.DataSource getTargetDataSource() {
        Long shardKey = ShardContext.get();
        if (shardKey == null) {
            throw new IllegalStateException(
                "Shard key not set in context. Use ShardJdbcTemplate or set ShardContext manually."
            );
        }
        
        Shard shard = shardRouter.resolve(shardKey);
        return shard.dataSource();
    }
    
    public ShardRouter getShardRouter() {
        return shardRouter;
    }
}
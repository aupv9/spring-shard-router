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
     * Resolve target DataSource based on current shard context.
     * Falls back to shard 0 when no shard key is set — this covers framework
     * callbacks that run outside of a shard-aware operation (e.g. Hibernate schema
     * validation during startup, connection metadata probing by the pool).
     * Application code should always set a shard key via ShardJdbcTemplate or
     * ShardContext before executing SQL.
     */
    private javax.sql.DataSource getTargetDataSource() {
        Long shardKey = ShardContext.get();
        if (shardKey == null) {
            return shardRouter.getShard(0).dataSource();
        }

        Shard shard = shardRouter.resolve(shardKey);
        return shard.dataSource();
    }
    
    public ShardRouter getShardRouter() {
        return shardRouter;
    }
}
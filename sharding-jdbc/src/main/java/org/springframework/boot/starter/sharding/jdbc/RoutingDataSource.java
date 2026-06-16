package org.springframework.boot.starter.sharding.jdbc;

import org.springframework.boot.starter.sharding.core.MissingShardKeyException;
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
     *
     * <p><b>Startup phase</b> ({@link ShardContext#isFallbackAllowed()} == {@code true}):
     * falls back to shard-0 when no key is set. This covers Hibernate schema
     * validation and HikariCP pool probing that run before any request is served.
     *
     * <p><b>Runtime phase</b> ({@link ShardContext#isFallbackAllowed()} == {@code false}):
     * throws {@link MissingShardKeyException} when no key is set. This surfaces bugs
     * where application code forgot to route to a shard, preventing silent data
     * corruption from unkeyed writes landing on shard-0.
     */
    private javax.sql.DataSource getTargetDataSource() {
        Long shardKey = ShardContext.get();
        if (shardKey == null) {
            if (ShardContext.isFallbackAllowed()) {
                return shardRouter.getShard(0).dataSource();
            }
            throw new MissingShardKeyException();
        }

        Shard shard = shardRouter.resolve(shardKey);
        return shard.dataSource();
    }
    
    public ShardRouter getShardRouter() {
        return shardRouter;
    }
}
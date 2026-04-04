package org.springframework.boot.starter.sharding.readwrite;

import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shard-aware {@link DataSource} that routes to primary or read replica based on:
 * <ol>
 *   <li>{@link ShardContext#isReadOnly()} — explicitly set by application code</li>
 *   <li>{@link TransactionSynchronizationManager#isCurrentTransactionReadOnly()} —
 *       automatically set by Spring when {@code @Transactional(readOnly=true)} is active</li>
 * </ol>
 *
 * <p>If neither flag is {@code true}, or if the resolved shard has no replicas,
 * the primary DataSource is used — identical behaviour to {@link
 * org.springframework.boot.starter.sharding.jdbc.RoutingDataSource}.
 *
 * <p>Replicas are load-balanced with a per-shard round-robin counter.
 */
public class ReadWriteRoutingDataSource extends AbstractDataSource {

    private final ShardRouter shardRouter;
    /** Round-robin counters keyed by shard index. */
    private final Map<Integer, AtomicInteger> replicaCounters = new ConcurrentHashMap<>();

    public ReadWriteRoutingDataSource(ShardRouter shardRouter) {
        this.shardRouter = shardRouter;
        // Pre-initialise counters for known shards
        for (int i = 0; i < shardRouter.getShardCount(); i++) {
            replicaCounters.put(i, new AtomicInteger(0));
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getTargetDataSource().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getTargetDataSource().getConnection(username, password);
    }

    private DataSource getTargetDataSource() {
        Long shardKey = ShardContext.get();
        if (shardKey == null) {
            // Fall back to shard-0 primary for schema validation / pool probing
            return shardRouter.getShard(0).dataSource();
        }

        Shard shard = shardRouter.resolve(shardKey);

        if (isReadOperation() && shard.hasReadReplicas()) {
            return selectReplica(shard);
        }

        return shard.dataSource();
    }

    private boolean isReadOperation() {
        return ShardContext.isReadOnly()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    private DataSource selectReplica(Shard shard) {
        AtomicInteger counter = replicaCounters.computeIfAbsent(
            shard.index(), k -> new AtomicInteger(0));
        int size = shard.readReplicaDataSources().size();
        int idx = Math.abs(counter.getAndIncrement() % size);
        return shard.readReplicaDataSources().get(idx);
    }
}

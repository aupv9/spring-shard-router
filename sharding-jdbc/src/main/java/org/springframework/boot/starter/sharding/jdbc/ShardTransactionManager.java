package org.springframework.boot.starter.sharding.jdbc;

import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;

import javax.sql.DataSource;

/**
 * Shard-aware transaction manager
 * Extends DataSourceTransactionManager to work with RoutingDataSource
 * 
 * Note: This provides single-shard transactions only
 * Cross-shard transactions require distributed transaction coordinator
 */
public class ShardTransactionManager extends DataSourceTransactionManager {
    
    public ShardTransactionManager(DataSource routingDataSource) {
        super(routingDataSource);
        setNestedTransactionAllowed(true);
    }
    
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // No ShardContext check here: the DataSource is wrapped with LazyConnectionDataSourceProxy
        // so the actual RoutingDataSource.getConnection() is deferred until the first SQL statement,
        // by which time ShardJdbcTemplate has already set ShardContext. Checking here would always
        // throw because @Transactional begins before the method body runs.
        super.doBegin(transaction, definition);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        try {
            super.doCommit(status);
        } catch (Exception e) {
            logger.error("Transaction commit failed for shard key: " + ShardContext.get(), e);
            throw e;
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        try {
            super.doRollback(status);
        } catch (Exception e) {
            logger.error("Transaction rollback failed for shard key: " + ShardContext.get(), e);
            throw e;
        }
    }
}
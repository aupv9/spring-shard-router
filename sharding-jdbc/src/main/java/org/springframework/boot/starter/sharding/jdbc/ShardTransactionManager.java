package org.springframework.boot.starter.sharding.jdbc;

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
        // Ensure shard context is available before starting transaction
        if (!isShardContextAvailable()) {
            throw new IllegalStateException(
                "Shard context not available. Ensure shard key is set before starting transaction."
            );
        }
        
        super.doBegin(transaction, definition);
    }
    
    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        try {
            super.doCommit(status);
        } catch (Exception e) {
            // Log shard context for debugging
            logger.error("Transaction commit failed. Shard context available: " + 
                isShardContextAvailable(), e);
            throw e;
        }
    }
    
    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        try {
            super.doRollback(status);
        } catch (Exception e) {
            // Log shard context for debugging
            logger.error("Transaction rollback failed. Shard context available: " + 
                isShardContextAvailable(), e);
            throw e;
        }
    }
    
    private boolean isShardContextAvailable() {
        try {
            // Try to get a connection to verify shard context
            getDataSource().getConnection().close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
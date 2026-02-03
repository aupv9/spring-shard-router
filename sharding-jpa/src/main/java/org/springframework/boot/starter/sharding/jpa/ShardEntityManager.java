package org.springframework.boot.starter.sharding.jpa;

import org.springframework.boot.starter.sharding.core.ShardContext;
import jakarta.persistence.*;
import java.util.List;
import java.util.Map;

/**
 * Simplified shard-aware EntityManager that automatically manages shard context
 * Wraps the routing EntityManager with shard key management
 */
public class ShardEntityManager {
    
    private final EntityManager delegate;
    
    public ShardEntityManager(EntityManager delegate) {
        this.delegate = delegate;
    }
    
    // Shard-aware operations
    
    public <T> T find(long shardKey, Class<T> entityClass, Object primaryKey) {
        return executeWithShardKey(shardKey, () -> delegate.find(entityClass, primaryKey));
    }
    
    public void persist(long shardKey, Object entity) {
        executeWithShardKey(shardKey, () -> {
            delegate.persist(entity);
            return null;
        });
    }
    
    public <T> T merge(long shardKey, T entity) {
        return executeWithShardKey(shardKey, () -> delegate.merge(entity));
    }
    
    public void remove(long shardKey, Object entity) {
        executeWithShardKey(shardKey, () -> {
            delegate.remove(entity);
            return null;
        });
    }
    
    public Query createQuery(long shardKey, String qlString) {
        return executeWithShardKey(shardKey, () -> delegate.createQuery(qlString));
    }
    
    public <T> TypedQuery<T> createQuery(long shardKey, String qlString, Class<T> resultClass) {
        return executeWithShardKey(shardKey, () -> delegate.createQuery(qlString, resultClass));
    }
    
    public Query createNativeQuery(long shardKey, String sqlString) {
        return executeWithShardKey(shardKey, () -> delegate.createNativeQuery(sqlString));
    }
    
    // Core execution method with shard context management
    private <T> T executeWithShardKey(long shardKey, ShardOperation<T> operation) {
        try {
            ShardContext.set(shardKey);
            return operation.execute();
        } catch (Exception e) {
            throw new RuntimeException("Shard operation failed for key: " + shardKey, e);
        } finally {
            ShardContext.clear();
        }
    }
    
    @FunctionalInterface
    private interface ShardOperation<T> {
        T execute();
    }
    
    // Delegate common methods to the underlying EntityManager
    
    public void flush() {
        delegate.flush();
    }
    
    public void clear() {
        delegate.clear();
    }
    
    public boolean isOpen() {
        return delegate.isOpen();
    }
    
    public void close() {
        delegate.close();
    }
    
    public EntityTransaction getTransaction() {
        return delegate.getTransaction();
    }
    
    // Get underlying EntityManager for advanced operations
    public EntityManager getDelegate() {
        return delegate;
    }
}
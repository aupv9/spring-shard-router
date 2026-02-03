package org.springframework.boot.starter.sharding.jpa;

import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.FluentQuery;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Base interface for shard-aware JPA repositories
 * Provides shard-aware versions of common repository operations
 */
public interface ShardJpaRepository<T, ID> extends JpaRepository<T, ID> {
    
    // Shard-aware find operations
    
    default Optional<T> findById(long shardKey, ID id) {
        return executeWithShardKey(shardKey, () -> findById(id));
    }
    
    default List<T> findAllById(long shardKey, Iterable<ID> ids) {
        return executeWithShardKey(shardKey, () -> findAllById(ids));
    }
    
    default List<T> findAll(long shardKey) {
        return executeWithShardKey(shardKey, () -> findAll());
    }
    
    default List<T> findAll(long shardKey, Sort sort) {
        return executeWithShardKey(shardKey, () -> findAll(sort));
    }
    
    default Page<T> findAll(long shardKey, Pageable pageable) {
        return executeWithShardKey(shardKey, () -> findAll(pageable));
    }
    
    // Shard-aware save operations
    
    default <S extends T> S save(long shardKey, S entity) {
        return executeWithShardKey(shardKey, () -> save(entity));
    }
    
    default <S extends T> List<S> saveAll(long shardKey, Iterable<S> entities) {
        return executeWithShardKey(shardKey, () -> saveAll(entities));
    }
    
    default <S extends T> S saveAndFlush(long shardKey, S entity) {
        return executeWithShardKey(shardKey, () -> saveAndFlush(entity));
    }
    
    default <S extends T> List<S> saveAllAndFlush(long shardKey, Iterable<S> entities) {
        return executeWithShardKey(shardKey, () -> saveAllAndFlush(entities));
    }
    
    // Shard-aware delete operations
    
    default void deleteById(long shardKey, ID id) {
        executeWithShardKey(shardKey, () -> {
            deleteById(id);
            return null;
        });
    }
    
    default void delete(long shardKey, T entity) {
        executeWithShardKey(shardKey, () -> {
            delete(entity);
            return null;
        });
    }
    
    default void deleteAllById(long shardKey, Iterable<? extends ID> ids) {
        executeWithShardKey(shardKey, () -> {
            deleteAllById(ids);
            return null;
        });
    }
    
    default void deleteAll(long shardKey, Iterable<? extends T> entities) {
        executeWithShardKey(shardKey, () -> {
            deleteAll(entities);
            return null;
        });
    }
    
    default void deleteAll(long shardKey) {
        executeWithShardKey(shardKey, () -> {
            deleteAll();
            return null;
        });
    }
    
    // Shard-aware count and exists operations
    
    default long count(long shardKey) {
        return executeWithShardKey(shardKey, () -> count());
    }
    
    default boolean existsById(long shardKey, ID id) {
        return executeWithShardKey(shardKey, () -> existsById(id));
    }
    
    // Shard-aware Example operations
    
    default <S extends T> Optional<S> findOne(long shardKey, Example<S> example) {
        return executeWithShardKey(shardKey, () -> findOne(example));
    }
    
    default <S extends T> List<S> findAll(long shardKey, Example<S> example) {
        return executeWithShardKey(shardKey, () -> findAll(example));
    }
    
    default <S extends T> List<S> findAll(long shardKey, Example<S> example, Sort sort) {
        return executeWithShardKey(shardKey, () -> findAll(example, sort));
    }
    
    default <S extends T> Page<S> findAll(long shardKey, Example<S> example, Pageable pageable) {
        return executeWithShardKey(shardKey, () -> findAll(example, pageable));
    }
    
    default <S extends T> long count(long shardKey, Example<S> example) {
        return executeWithShardKey(shardKey, () -> count(example));
    }
    
    default <S extends T> boolean exists(long shardKey, Example<S> example) {
        return executeWithShardKey(shardKey, () -> exists(example));
    }
    
    default <S extends T, R> R findBy(long shardKey, Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        return executeWithShardKey(shardKey, () -> findBy(example, queryFunction));
    }
    
    // Core execution method with shard context management
    
    default <R> R executeWithShardKey(long shardKey, ShardOperation<R> operation) {
        try {
            ShardContext.set(shardKey);
            return operation.execute();
        } catch (Exception e) {
            throw new RuntimeException("Shard repository operation failed for key: " + shardKey, e);
        } finally {
            ShardContext.clear();
        }
    }
    
    @FunctionalInterface
    interface ShardOperation<R> {
        R execute();
    }
}
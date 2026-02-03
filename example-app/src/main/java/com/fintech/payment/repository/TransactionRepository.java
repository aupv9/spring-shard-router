package com.fintech.payment.repository;

import com.fintech.payment.entity.Transaction;
import org.springframework.boot.starter.sharding.jpa.ShardJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Shard-aware repository for Transaction entities
 * Extends ShardJpaRepository for automatic shard routing
 */
@Repository
public interface TransactionRepository extends ShardJpaRepository<Transaction, Long> {
    
    /**
     * Find transactions by account ID with shard key
     */
    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId ORDER BY t.createdAt DESC")
    List<Transaction> findByAccountIdOrderByCreatedAtDesc(long shardKey, @Param("accountId") Long accountId);
    
    /**
     * Find transactions by account ID with pagination
     */
    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId")
    Page<Transaction> findByAccountId(long shardKey, @Param("accountId") Long accountId, Pageable pageable);
    
    /**
     * Find transactions by account ID and status
     */
    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId AND t.status = :status")
    List<Transaction> findByAccountIdAndStatus(long shardKey, @Param("accountId") Long accountId, @Param("status") Transaction.TransactionStatus status);
    
    /**
     * Find transactions by account ID within date range
     */
    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId AND t.createdAt BETWEEN :startDate AND :endDate ORDER BY t.createdAt DESC")
    List<Transaction> findByAccountIdAndDateRange(
        long shardKey, 
        @Param("accountId") Long accountId, 
        @Param("startDate") LocalDateTime startDate, 
        @Param("endDate") LocalDateTime endDate
    );
    
    /**
     * Count transactions by account ID
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.accountId = :accountId")
    long countByAccountId(long shardKey, @Param("accountId") Long accountId);
    
    /**
     * Find recent transactions (last N transactions)
     */
    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId ORDER BY t.createdAt DESC LIMIT :limit")
    List<Transaction> findRecentTransactions(long shardKey, @Param("accountId") Long accountId, @Param("limit") int limit);
}
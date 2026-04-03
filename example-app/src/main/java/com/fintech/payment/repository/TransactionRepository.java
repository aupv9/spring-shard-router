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
import java.util.UUID;

/**
 * Shard-aware repository for Transaction entities.
 *
 * Pattern: public default methods set shard context via executeWithShardKey(),
 * then delegate to internal @Query methods that have NO shardKey parameter.
 * Spring Data JPA binds all method parameters to the JPQL — adding shardKey
 * directly to a @Query method would cause a binding error.
 */
@Repository
public interface TransactionRepository extends ShardJpaRepository<Transaction, UUID> {

    default List<Transaction> findByAccountIdOrderByCreatedAtDesc(long shardKey, Long accountId) {
        return executeWithShardKey(shardKey, () -> findByAccountIdOrderByCreatedAtDescInternal(accountId));
    }

    default Page<Transaction> findByAccountId(long shardKey, Long accountId, Pageable pageable) {
        return executeWithShardKey(shardKey, () -> findByAccountIdInternal(accountId, pageable));
    }

    default List<Transaction> findByAccountIdAndStatus(long shardKey, Long accountId, Transaction.TransactionStatus status) {
        return executeWithShardKey(shardKey, () -> findByAccountIdAndStatusInternal(accountId, status));
    }

    default List<Transaction> findByAccountIdAndDateRange(long shardKey, Long accountId,
                                                           LocalDateTime startDate, LocalDateTime endDate) {
        return executeWithShardKey(shardKey, () -> findByAccountIdAndDateRangeInternal(accountId, startDate, endDate));
    }

    default long countByAccountId(long shardKey, Long accountId) {
        return executeWithShardKey(shardKey, () -> countByAccountIdInternal(accountId));
    }

    default List<Transaction> findRecentTransactions(long shardKey, Long accountId, int limit) {
        return executeWithShardKey(shardKey, () -> findRecentTransactionsInternal(accountId, Pageable.ofSize(limit)))
            .getContent();
    }

    // Internal @Query methods — no shardKey param

    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId ORDER BY t.createdAt DESC")
    List<Transaction> findByAccountIdOrderByCreatedAtDescInternal(@Param("accountId") Long accountId);

    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId")
    Page<Transaction> findByAccountIdInternal(@Param("accountId") Long accountId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId AND t.status = :status")
    List<Transaction> findByAccountIdAndStatusInternal(
        @Param("accountId") Long accountId,
        @Param("status") Transaction.TransactionStatus status
    );

    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId AND t.createdAt BETWEEN :startDate AND :endDate ORDER BY t.createdAt DESC")
    List<Transaction> findByAccountIdAndDateRangeInternal(
        @Param("accountId") Long accountId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.accountId = :accountId")
    long countByAccountIdInternal(@Param("accountId") Long accountId);

    // Uses Pageable for limit instead of JPQL LIMIT (not standard in JPQL)
    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId ORDER BY t.createdAt DESC")
    Page<Transaction> findRecentTransactionsInternal(@Param("accountId") Long accountId, Pageable pageable);
}

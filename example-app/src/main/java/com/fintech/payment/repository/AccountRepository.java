package com.fintech.payment.repository;

import com.fintech.payment.entity.Account;
import org.springframework.boot.starter.sharding.jpa.ShardJpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Shard-aware repository for Account entities
 * Extends ShardJpaRepository for automatic shard routing
 */
@Repository
public interface AccountRepository extends ShardJpaRepository<Account, Long> {
    
    /**
     * Find account by account ID with shard key
     */
    default Optional<Account> findByAccountId(long shardKey, Long accountId) {
        return findById(shardKey, accountId);
    }
    
    /**
     * Update account balance with shard key
     */
    @Modifying
    @Query("UPDATE Account a SET a.balance = a.balance + :amount WHERE a.accountId = :accountId")
    int updateBalance(long shardKey, @Param("accountId") Long accountId, @Param("amount") BigDecimal amount);
    
    /**
     * Check if account exists with sufficient balance
     */
    @Query("SELECT CASE WHEN a.balance >= :amount THEN true ELSE false END FROM Account a WHERE a.accountId = :accountId")
    boolean hasSufficientBalance(long shardKey, @Param("accountId") Long accountId, @Param("amount") BigDecimal amount);
    
    /**
     * Get account balance
     */
    @Query("SELECT a.balance FROM Account a WHERE a.accountId = :accountId")
    Optional<BigDecimal> getBalance(long shardKey, @Param("accountId") Long accountId);
}
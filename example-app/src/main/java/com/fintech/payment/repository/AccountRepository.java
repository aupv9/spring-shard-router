package com.fintech.payment.repository;

import com.fintech.payment.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.boot.starter.sharding.jpa.ShardJpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Shard-aware repository for Account entities.
 *
 * Pattern for custom @Query methods:
 *   - Public API: default method accepts shardKey + delegates to executeWithShardKey()
 *   - Internal: @Query method has NO shardKey param (Spring Data JPA would try to bind it otherwise)
 */
@Repository
public interface AccountRepository extends ShardJpaRepository<Account, Long> {

    default Optional<Account> findByAccountId(long shardKey, Long accountId) {
        return findById(shardKey, accountId);
    }

    /** SELECT FOR UPDATE — use this inside @Transactional when updating balance to prevent lost updates. */
    default Optional<Account> findByAccountIdForUpdate(long shardKey, Long accountId) {
        return executeWithShardKey(shardKey, () -> findByIdForUpdateInternal(accountId));
    }

    default int updateBalance(long shardKey, Long accountId, BigDecimal amount) {
        return executeWithShardKey(shardKey, () -> updateBalanceInternal(accountId, amount));
    }

    default boolean hasSufficientBalance(long shardKey, Long accountId, BigDecimal amount) {
        return executeWithShardKey(shardKey, () -> hasSufficientBalanceInternal(accountId, amount));
    }

    default Optional<BigDecimal> getBalance(long shardKey, Long accountId) {
        return executeWithShardKey(shardKey, () -> getBalanceInternal(accountId));
    }

    // Internal @Query methods — no shardKey param, called only via executeWithShardKey
    @Modifying
    @Query("UPDATE Account a SET a.balance = a.balance + :amount WHERE a.accountId = :accountId")
    int updateBalanceInternal(@Param("accountId") Long accountId, @Param("amount") BigDecimal amount);

    @Query("SELECT CASE WHEN a.balance >= :amount THEN true ELSE false END FROM Account a WHERE a.accountId = :accountId")
    boolean hasSufficientBalanceInternal(@Param("accountId") Long accountId, @Param("amount") BigDecimal amount);

    @Query("SELECT a.balance FROM Account a WHERE a.accountId = :accountId")
    Optional<BigDecimal> getBalanceInternal(@Param("accountId") Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountId = :accountId")
    Optional<Account> findByIdForUpdateInternal(@Param("accountId") Long accountId);
}

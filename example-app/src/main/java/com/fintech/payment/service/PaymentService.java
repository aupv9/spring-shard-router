package com.fintech.payment.service;

import org.springframework.boot.starter.sharding.jdbc.ShardJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Payment service demonstrating sharding usage
 * Business logic is completely unaware of sharding implementation
 */
@Service
public class PaymentService {
    
    private final ShardJdbcTemplate shardJdbc;
    
    public PaymentService(ShardJdbcTemplate shardJdbc) {
        this.shardJdbc = shardJdbc;
    }
    
    /**
     * Process payment - automatically routed to correct shard
     */
    @Transactional("shardTransactionManager")
    public void processPayment(long accountId, BigDecimal amount, String description) {
        // Insert transaction record
        shardJdbc.update(
            accountId,
            """
            INSERT INTO transactions (account_id, amount, description, created_at, status) 
            VALUES (?, ?, ?, ?, 'PENDING')
            """,
            accountId, amount, description, LocalDateTime.now()
        );
        
        // Update account balance
        int updated = shardJdbc.update(
            accountId,
            "UPDATE accounts SET balance = balance - ?, updated_at = ? WHERE account_id = ?",
            amount, LocalDateTime.now(), accountId
        );
        
        if (updated == 0) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
        
        // Mark transaction as completed
        shardJdbc.update(
            accountId,
            "UPDATE transactions SET status = 'COMPLETED' WHERE account_id = ? AND status = 'PENDING'",
            accountId
        );
    }
    
    /**
     * Get account balance
     */
    public BigDecimal getBalance(long accountId) {
        return shardJdbc.queryForObject(
            accountId,
            "SELECT balance FROM accounts WHERE account_id = ?",
            BigDecimal.class,
            accountId
        );
    }
    
    /**
     * Get transaction history for account
     */
    public List<Map<String, Object>> getTransactionHistory(long accountId, int limit) {
        return shardJdbc.queryForList(
            accountId,
            """
            SELECT transaction_id, amount, description, created_at, status 
            FROM transactions 
            WHERE account_id = ? 
            ORDER BY created_at DESC 
            LIMIT ?
            """,
            accountId, limit
        );
    }
    
    /**
     * Create new account
     */
    @Transactional("shardTransactionManager")
    public void createAccount(long accountId, BigDecimal initialBalance) {
        shardJdbc.update(
            accountId,
            """
            INSERT INTO accounts (account_id, balance, created_at, updated_at) 
            VALUES (?, ?, ?, ?)
            """,
            accountId, initialBalance, LocalDateTime.now(), LocalDateTime.now()
        );
    }
    
    /**
     * Batch payment processing
     */
    @Transactional("shardTransactionManager")
    public void processBatchPayments(long accountId, List<PaymentRequest> payments) {
        List<Object[]> batchArgs = payments.stream()
            .map(p -> new Object[]{accountId, p.amount(), p.description(), LocalDateTime.now()})
            .toList();
            
        shardJdbc.batchUpdate(
            accountId,
            """
            INSERT INTO transactions (account_id, amount, description, created_at, status) 
            VALUES (?, ?, ?, ?, 'COMPLETED')
            """,
            batchArgs
        );
    }
    
    public record PaymentRequest(BigDecimal amount, String description) {}
}
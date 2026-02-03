package com.fintech.payment.service;

import com.fintech.payment.entity.Account;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.repository.AccountRepository;
import com.fintech.payment.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Payment service using JPA repositories with sharding
 * Demonstrates clean JPA usage with automatic shard routing
 */
@Service
public class PaymentJpaService {
    
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    
    public PaymentJpaService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }
    
    /**
     * Process payment using JPA entities - automatically routed to correct shard
     */
    @Transactional("shardJpaTransactionManager")
    public void processPayment(long accountId, BigDecimal amount, String description) {
        // Check if account exists and has sufficient balance
        Optional<Account> accountOpt = accountRepository.findByAccountId(accountId, accountId);
        if (accountOpt.isEmpty()) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
        
        Account account = accountOpt.get();
        if (account.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient balance");
        }
        
        // Create transaction record
        Transaction transaction = new Transaction(accountId, amount, description);
        transactionRepository.save(accountId, transaction);
        
        // Update account balance
        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(accountId, account);
        
        // Mark transaction as completed
        transaction.setStatus(Transaction.TransactionStatus.COMPLETED);
        transactionRepository.save(accountId, transaction);
    }
    
    /**
     * Get account balance using JPA
     */
    public BigDecimal getBalance(long accountId) {
        return accountRepository.getBalance(accountId, accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }
    
    /**
     * Get transaction history using JPA
     */
    public List<Transaction> getTransactionHistory(long accountId, int limit) {
        return transactionRepository.findRecentTransactions(accountId, accountId, limit);
    }
    
    /**
     * Create new account using JPA
     */
    @Transactional("shardJpaTransactionManager")
    public Account createAccount(long accountId, BigDecimal initialBalance) {
        Account account = new Account(accountId, initialBalance);
        return accountRepository.save(accountId, account);
    }
    
    /**
     * Transfer money between accounts (same shard only)
     */
    @Transactional("shardJpaTransactionManager")
    public void transferMoney(long fromAccountId, long toAccountId, BigDecimal amount, String description) {
        // Verify both accounts are on the same shard
        if (!onSameShard(fromAccountId, toAccountId)) {
            throw new IllegalArgumentException("Cross-shard transfers not supported");
        }
        
        // Get both accounts
        Optional<Account> fromAccountOpt = accountRepository.findByAccountId(fromAccountId, fromAccountId);
        Optional<Account> toAccountOpt = accountRepository.findByAccountId(fromAccountId, toAccountId);
        
        if (fromAccountOpt.isEmpty() || toAccountOpt.isEmpty()) {
            throw new IllegalArgumentException("One or both accounts not found");
        }
        
        Account fromAccount = fromAccountOpt.get();
        Account toAccount = toAccountOpt.get();
        
        // Check sufficient balance
        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient balance");
        }
        
        // Create debit transaction
        Transaction debitTx = new Transaction(fromAccountId, amount.negate(), "Transfer to " + toAccountId + ": " + description);
        debitTx.setStatus(Transaction.TransactionStatus.COMPLETED);
        transactionRepository.save(fromAccountId, debitTx);
        
        // Create credit transaction
        Transaction creditTx = new Transaction(toAccountId, amount, "Transfer from " + fromAccountId + ": " + description);
        creditTx.setStatus(Transaction.TransactionStatus.COMPLETED);
        transactionRepository.save(fromAccountId, creditTx);
        
        // Update balances
        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
        toAccount.setBalance(toAccount.getBalance().add(amount));
        
        accountRepository.save(fromAccountId, fromAccount);
        accountRepository.save(fromAccountId, toAccount);
    }
    
    /**
     * Get account details
     */
    public Optional<Account> getAccount(long accountId) {
        return accountRepository.findByAccountId(accountId, accountId);
    }
    
    /**
     * Get transactions by date range
     */
    public List<Transaction> getTransactionsByDateRange(long accountId, LocalDateTime startDate, LocalDateTime endDate) {
        return transactionRepository.findByAccountIdAndDateRange(accountId, accountId, startDate, endDate);
    }
    
    /**
     * Get pending transactions
     */
    public List<Transaction> getPendingTransactions(long accountId) {
        return transactionRepository.findByAccountIdAndStatus(accountId, accountId, Transaction.TransactionStatus.PENDING);
    }
    
    /**
     * Check if two accounts are on the same shard
     */
    private boolean onSameShard(long accountId1, long accountId2) {
        // Simple hash-based check - in real implementation, use ShardRouter
        return (accountId1 % 3) == (accountId2 % 3);
    }
}
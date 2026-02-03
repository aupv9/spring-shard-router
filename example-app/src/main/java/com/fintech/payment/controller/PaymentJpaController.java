package com.fintech.payment.controller;

import com.fintech.payment.entity.Account;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.service.PaymentJpaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * REST controller for JPA-based payment operations
 * Demonstrates clean API using JPA entities with sharding
 */
@RestController
@RequestMapping("/api/jpa/payments")
public class PaymentJpaController {
    
    private final PaymentJpaService paymentJpaService;
    
    public PaymentJpaController(PaymentJpaService paymentJpaService) {
        this.paymentJpaService = paymentJpaService;
    }
    
    /**
     * Process a payment using JPA
     */
    @PostMapping("/process")
    public ResponseEntity<String> processPayment(@RequestBody PaymentRequest request) {
        try {
            paymentJpaService.processPayment(
                request.accountId(), 
                request.amount(), 
                request.description()
            );
            return ResponseEntity.ok("Payment processed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Payment failed: " + e.getMessage());
        }
    }
    
    /**
     * Transfer money between accounts
     */
    @PostMapping("/transfer")
    public ResponseEntity<String> transferMoney(@RequestBody TransferRequest request) {
        try {
            paymentJpaService.transferMoney(
                request.fromAccountId(),
                request.toAccountId(),
                request.amount(),
                request.description()
            );
            return ResponseEntity.ok("Transfer completed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Transfer failed: " + e.getMessage());
        }
    }
    
    /**
     * Get account details
     */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<Account> getAccount(@PathVariable long accountId) {
        Optional<Account> account = paymentJpaService.getAccount(accountId);
        return account.map(ResponseEntity::ok)
                     .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Get account balance
     */
    @GetMapping("/balance/{accountId}")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable long accountId) {
        try {
            BigDecimal balance = paymentJpaService.getBalance(accountId);
            return ResponseEntity.ok(new BalanceResponse(accountId, balance));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }
    
    /**
     * Get transaction history
     */
    @GetMapping("/history/{accountId}")
    public ResponseEntity<List<Transaction>> getHistory(
            @PathVariable long accountId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            List<Transaction> history = paymentJpaService.getTransactionHistory(accountId, limit);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }
    
    /**
     * Get transactions by date range
     */
    @GetMapping("/history/{accountId}/range")
    public ResponseEntity<List<Transaction>> getHistoryByDateRange(
            @PathVariable long accountId,
            @RequestParam LocalDateTime startDate,
            @RequestParam LocalDateTime endDate) {
        try {
            List<Transaction> transactions = paymentJpaService.getTransactionsByDateRange(accountId, startDate, endDate);
            return ResponseEntity.ok(transactions);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }
    
    /**
     * Get pending transactions
     */
    @GetMapping("/pending/{accountId}")
    public ResponseEntity<List<Transaction>> getPendingTransactions(@PathVariable long accountId) {
        try {
            List<Transaction> pending = paymentJpaService.getPendingTransactions(accountId);
            return ResponseEntity.ok(pending);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }
    
    /**
     * Create new account
     */
    @PostMapping("/accounts")
    public ResponseEntity<Account> createAccount(@RequestBody CreateAccountRequest request) {
        try {
            Account account = paymentJpaService.createAccount(request.accountId(), request.initialBalance());
            return ResponseEntity.ok(account);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }
    
    // DTOs
    
    public record PaymentRequest(
        long accountId,
        BigDecimal amount,
        String description
    ) {}
    
    public record TransferRequest(
        long fromAccountId,
        long toAccountId,
        BigDecimal amount,
        String description
    ) {}
    
    public record BalanceResponse(
        long accountId,
        BigDecimal balance
    ) {}
    
    public record CreateAccountRequest(
        long accountId,
        BigDecimal initialBalance
    ) {}
}
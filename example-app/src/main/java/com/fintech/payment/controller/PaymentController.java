package com.fintech.payment.controller;

import com.fintech.payment.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST controller for payment operations
 * Demonstrates clean API without any sharding concerns
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    
    private final PaymentService paymentService;
    
    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }
    
    /**
     * Process a payment
     */
    @PostMapping("/process")
    public ResponseEntity<String> processPayment(@RequestBody PaymentRequest request) {
        try {
            paymentService.processPayment(
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
     * Get account balance
     */
    @GetMapping("/balance/{accountId}")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable long accountId) {
        try {
            BigDecimal balance = paymentService.getBalance(accountId);
            return ResponseEntity.ok(new BalanceResponse(accountId, balance));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }
    
    /**
     * Get transaction history
     */
    @GetMapping("/history/{accountId}")
    public ResponseEntity<List<Map<String, Object>>> getHistory(
            @PathVariable long accountId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            List<Map<String, Object>> history = paymentService.getTransactionHistory(accountId, limit);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }
    
    /**
     * Create new account
     */
    @PostMapping("/accounts")
    public ResponseEntity<String> createAccount(@RequestBody CreateAccountRequest request) {
        try {
            paymentService.createAccount(request.accountId(), request.initialBalance());
            return ResponseEntity.ok("Account created successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Account creation failed: " + e.getMessage());
        }
    }
    
    // DTOs
    
    public record PaymentRequest(
        long accountId,
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
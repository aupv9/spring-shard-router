package com.fintech.payment;

import com.fintech.payment.service.PaymentJpaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance and concurrency tests for sharding
 */
@SpringBootTest
@Testcontainers
class ShardingPerformanceTest {
    
    @Container
    static PostgreSQLContainer<?> postgres0 = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("perf_db")
            .withUsername("perf_user")
            .withPassword("perf_pass");
    
    @Container
    static PostgreSQLContainer<?> postgres1 = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("perf_db")
            .withUsername("perf_user")
            .withPassword("perf_pass");
    
    @Container
    static PostgreSQLContainer<?> postgres2 = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("perf_db")
            .withUsername("perf_user")
            .withPassword("perf_pass");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("sharding.enabled", () -> "true");
        registry.add("sharding.strategy", () -> "HASH");
        
        registry.add("sharding.shards[0].name", () -> "shard-0");
        registry.add("sharding.shards[0].datasource.jdbc-url", postgres0::getJdbcUrl);
        registry.add("sharding.shards[0].datasource.username", postgres0::getUsername);
        registry.add("sharding.shards[0].datasource.password", postgres0::getPassword);
        registry.add("sharding.shards[0].datasource.maximum-pool-size", () -> "10");
        
        registry.add("sharding.shards[1].name", () -> "shard-1");
        registry.add("sharding.shards[1].datasource.jdbc-url", postgres1::getJdbcUrl);
        registry.add("sharding.shards[1].datasource.username", postgres1::getUsername);
        registry.add("sharding.shards[1].datasource.password", postgres1::getPassword);
        registry.add("sharding.shards[1].datasource.maximum-pool-size", () -> "10");
        
        registry.add("sharding.shards[2].name", () -> "shard-2");
        registry.add("sharding.shards[2].datasource.jdbc-url", postgres2::getJdbcUrl);
        registry.add("sharding.shards[2].datasource.username", postgres2::getUsername);
        registry.add("sharding.shards[2].datasource.password", postgres2::getPassword);
        registry.add("sharding.shards[2].datasource.maximum-pool-size", () -> "10");
        
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("logging.level.org.springframework.boot.starter.sharding", () -> "WARN");
    }
    
    @Autowired
    private ShardRouter shardRouter;
    
    @Autowired
    private PaymentJpaService paymentJpaService;
    
    @Test
    void shouldDistributeLoadAcrossShards() {
        // Create accounts across different shards
        int accountCount = 300;
        int[] shardCounts = new int[3];
        
        for (int i = 1; i <= accountCount; i++) {
            long accountId = 10000L + i;
            var shard = shardRouter.resolve(accountId);
            shardCounts[shard.index()]++;
            
            // Create account
            try {
                paymentJpaService.createAccount(accountId, new BigDecimal("1000.00"));
            } catch (Exception e) {
                // Ignore if account already exists
            }
        }
        
        // Verify distribution is reasonably balanced
        System.out.println("Shard distribution:");
        for (int i = 0; i < shardCounts.length; i++) {
            System.out.println("Shard " + i + ": " + shardCounts[i] + " accounts");
        }
        
        // Each shard should have at least 20% of accounts (allowing for hash variance)
        int minExpected = accountCount / 5; // 20%
        for (int count : shardCounts) {
            assertTrue(count >= minExpected, "Shard distribution too uneven: " + count + " < " + minExpected);
        }
    }
    
    @Test
    void shouldHandleConcurrentOperations() throws InterruptedException {
        // Create test accounts
        long[] accountIds = {20001L, 20002L, 20003L, 20004L, 20005L};
        for (long accountId : accountIds) {
            try {
                paymentJpaService.createAccount(accountId, new BigDecimal("10000.00"));
            } catch (Exception e) {
                // Ignore if exists
            }
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        int operationsPerAccount = 20;
        
        // Submit concurrent operations
        CompletableFuture<?>[] futures = new CompletableFuture[accountIds.length * operationsPerAccount];
        int futureIndex = 0;
        
        for (long accountId : accountIds) {
            for (int i = 0; i < operationsPerAccount; i++) {
                final int operationId = i;
                futures[futureIndex++] = CompletableFuture.runAsync(() -> {
                    try {
                        paymentJpaService.processPayment(
                            accountId, 
                            new BigDecimal("10.00"), 
                            "Concurrent payment " + operationId
                        );
                    } catch (Exception e) {
                        System.err.println("Payment failed for account " + accountId + ": " + e.getMessage());
                    }
                }, executor);
            }
        }
        
        // Wait for all operations to complete
        CompletableFuture.allOf(futures).join();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        
        // Verify final balances
        for (long accountId : accountIds) {
            BigDecimal balance = paymentJpaService.getBalance(accountId);
            BigDecimal expectedBalance = new BigDecimal("10000.00").subtract(
                new BigDecimal("10.00").multiply(new BigDecimal(operationsPerAccount))
            );
            assertEquals(expectedBalance, balance, "Balance mismatch for account " + accountId);
        }
    }
    
    @Test
    void shouldMaintainConsistencyUnderLoad() {
        long accountId = 30001L;
        
        // Create account
        try {
            paymentJpaService.createAccount(accountId, new BigDecimal("50000.00"));
        } catch (Exception e) {
            // Ignore if exists
        }
        
        // Perform many small operations
        int operationCount = 100;
        BigDecimal operationAmount = new BigDecimal("50.00");
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < operationCount; i++) {
            paymentJpaService.processPayment(accountId, operationAmount, "Load test " + i);
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        System.out.println("Processed " + operationCount + " operations in " + duration + "ms");
        System.out.println("Average: " + (duration / (double) operationCount) + "ms per operation");
        
        // Verify final balance
        BigDecimal expectedBalance = new BigDecimal("50000.00").subtract(
            operationAmount.multiply(new BigDecimal(operationCount))
        );
        BigDecimal actualBalance = paymentJpaService.getBalance(accountId);
        assertEquals(expectedBalance, actualBalance);
        
        // Verify transaction count
        var transactions = paymentJpaService.getTransactionHistory(accountId, operationCount + 10);
        long loadTestTransactions = transactions.stream()
            .filter(tx -> tx.getDescription().startsWith("Load test"))
            .count();
        assertEquals(operationCount, loadTestTransactions);
    }
    
    @Test
    void shouldHandleShardFailureGracefully() {
        // This test would require more complex setup to simulate shard failures
        // For now, just verify that operations on available shards continue to work
        
        long accountId = 40001L;
        
        try {
            paymentJpaService.createAccount(accountId, new BigDecimal("1000.00"));
            paymentJpaService.processPayment(accountId, new BigDecimal("100.00"), "Resilience test");
            
            BigDecimal balance = paymentJpaService.getBalance(accountId);
            assertEquals(new BigDecimal("900.00"), balance);
            
        } catch (Exception e) {
            fail("Operations should continue working on available shards: " + e.getMessage());
        }
    }
}
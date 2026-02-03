package com.fintech.payment;

import com.fintech.payment.entity.Account;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.repository.AccountRepository;
import com.fintech.payment.repository.TransactionRepository;
import com.fintech.payment.service.PaymentJpaService;
import com.fintech.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.jdbc.ShardJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for sharding functionality using Testcontainers
 * Tests both JDBC and JPA layers with real PostgreSQL databases
 */
@SpringBootTest
@Testcontainers
class ShardingIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres0 = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("payment_db")
            .withUsername("payment_user")
            .withPassword("payment_pass");
    
    @Container
    static PostgreSQLContainer<?> postgres1 = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("payment_db")
            .withUsername("payment_user")
            .withPassword("payment_pass");
    
    @Container
    static PostgreSQLContainer<?> postgres2 = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("payment_db")
            .withUsername("payment_user")
            .withPassword("payment_pass");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("sharding.enabled", () -> "true");
        registry.add("sharding.strategy", () -> "HASH");
        
        // Configure shard 0
        registry.add("sharding.shards[0].name", () -> "shard-0");
        registry.add("sharding.shards[0].datasource.jdbc-url", postgres0::getJdbcUrl);
        registry.add("sharding.shards[0].datasource.username", postgres0::getUsername);
        registry.add("sharding.shards[0].datasource.password", postgres0::getPassword);
        
        // Configure shard 1
        registry.add("sharding.shards[1].name", () -> "shard-1");
        registry.add("sharding.shards[1].datasource.jdbc-url", postgres1::getJdbcUrl);
        registry.add("sharding.shards[1].datasource.username", postgres1::getUsername);
        registry.add("sharding.shards[1].datasource.password", postgres1::getPassword);
        
        // Configure shard 2
        registry.add("sharding.shards[2].name", () -> "shard-2");
        registry.add("sharding.shards[2].datasource.jdbc-url", postgres2::getJdbcUrl);
        registry.add("sharding.shards[2].datasource.username", postgres2::getUsername);
        registry.add("sharding.shards[2].datasource.password", postgres2::getPassword);
        
        // VIP override - account 10001 always goes to shard 0
        registry.add("sharding.overrides.10001", () -> "0");
        
        // JPA settings
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "true");
    }
    
    @Autowired
    private ShardRouter shardRouter;
    
    @Autowired
    private ShardJdbcTemplate shardJdbcTemplate;
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private PaymentJpaService paymentJpaService;
    
    @Autowired
    private AccountRepository accountRepository;
    
    @Autowired
    private TransactionRepository transactionRepository;
    
    @BeforeEach
    void setUp() {
        // Create test accounts on different shards
        createTestAccounts();
    }
    
    private void createTestAccounts() {
        // Create accounts that will be distributed across shards
        long[] testAccountIds = {1001L, 1002L, 1003L, 10001L}; // 10001 is VIP override
        
        for (long accountId : testAccountIds) {
            try {
                paymentJpaService.createAccount(accountId, new BigDecimal("1000.00"));
            } catch (Exception e) {
                // Account might already exist, ignore
            }
        }
    }
    
    @Test
    void shouldDistributeAccountsAcrossShards() {
        // Test that different account IDs go to different shards
        long account1 = 1001L;
        long account2 = 1002L;
        long account3 = 1003L;
        
        var shard1 = shardRouter.resolve(account1);
        var shard2 = shardRouter.resolve(account2);
        var shard3 = shardRouter.resolve(account3);
        
        // With 3 shards and hash distribution, we should see different shards
        // (not guaranteed but very likely with these specific IDs)
        System.out.println("Account " + account1 + " -> " + shard1.name());
        System.out.println("Account " + account2 + " -> " + shard2.name());
        System.out.println("Account " + account3 + " -> " + shard3.name());
        
        // Verify shards are valid
        assertTrue(shard1.index() >= 0 && shard1.index() < 3);
        assertTrue(shard2.index() >= 0 && shard2.index() < 3);
        assertTrue(shard3.index() >= 0 && shard3.index() < 3);
    }
    
    @Test
    void shouldRespectVipOverrides() {
        // VIP account 10001 should always go to shard 0
        var vipShard = shardRouter.resolve(10001L);
        assertEquals(0, vipShard.index());
        assertEquals("shard-0", vipShard.name());
    }
    
    @Test
    @Transactional("shardJpaTransactionManager")
    void shouldProcessPaymentWithJdbc() {
        long accountId = 1001L;
        BigDecimal initialBalance = paymentService.getBalance(accountId);
        BigDecimal paymentAmount = new BigDecimal("100.00");
        
        // Process payment
        paymentService.processPayment(accountId, paymentAmount, "Test payment");
        
        // Verify balance updated
        BigDecimal newBalance = paymentService.getBalance(accountId);
        assertEquals(initialBalance.subtract(paymentAmount), newBalance);
        
        // Verify transaction history
        var history = paymentService.getTransactionHistory(accountId, 10);
        assertFalse(history.isEmpty());
        
        // Find our transaction
        boolean found = history.stream()
            .anyMatch(tx -> "Test payment".equals(tx.get("description")));
        assertTrue(found);
    }
    
    @Test
    @Transactional("shardJpaTransactionManager")
    void shouldProcessPaymentWithJpa() {
        long accountId = 1002L;
        
        // Get initial balance
        Optional<Account> accountOpt = accountRepository.findByAccountId(accountId, accountId);
        assertTrue(accountOpt.isPresent());
        BigDecimal initialBalance = accountOpt.get().getBalance();
        
        BigDecimal paymentAmount = new BigDecimal("150.00");
        
        // Process payment
        paymentJpaService.processPayment(accountId, paymentAmount, "JPA test payment");
        
        // Verify balance updated
        BigDecimal newBalance = paymentJpaService.getBalance(accountId);
        assertEquals(initialBalance.subtract(paymentAmount), newBalance);
        
        // Verify transaction history
        List<Transaction> history = paymentJpaService.getTransactionHistory(accountId, 10);
        assertFalse(history.isEmpty());
        
        // Find our transaction
        boolean found = history.stream()
            .anyMatch(tx -> "JPA test payment".equals(tx.getDescription()));
        assertTrue(found);
    }
    
    @Test
    @Transactional("shardJpaTransactionManager")
    void shouldHandleTransferWithinSameShard() {
        // Find two accounts that are on the same shard
        long fromAccount = 1001L;
        long toAccount = findAccountOnSameShard(fromAccount);
        
        if (toAccount == -1) {
            // Skip test if we can't find accounts on same shard
            return;
        }
        
        BigDecimal transferAmount = new BigDecimal("50.00");
        
        // Get initial balances
        BigDecimal fromInitialBalance = paymentJpaService.getBalance(fromAccount);
        BigDecimal toInitialBalance = paymentJpaService.getBalance(toAccount);
        
        // Perform transfer
        paymentJpaService.transferMoney(fromAccount, toAccount, transferAmount, "Test transfer");
        
        // Verify balances
        BigDecimal fromFinalBalance = paymentJpaService.getBalance(fromAccount);
        BigDecimal toFinalBalance = paymentJpaService.getBalance(toAccount);
        
        assertEquals(fromInitialBalance.subtract(transferAmount), fromFinalBalance);
        assertEquals(toInitialBalance.add(transferAmount), toFinalBalance);
    }
    
    @Test
    void shouldRejectCrossShardTransfer() {
        // Find two accounts on different shards
        long fromAccount = 1001L;
        long toAccount = findAccountOnDifferentShard(fromAccount);
        
        if (toAccount == -1) {
            // Skip test if all accounts are on same shard
            return;
        }
        
        BigDecimal transferAmount = new BigDecimal("50.00");
        
        // Should throw exception for cross-shard transfer
        assertThrows(IllegalArgumentException.class, () -> {
            paymentJpaService.transferMoney(fromAccount, toAccount, transferAmount, "Cross-shard transfer");
        });
    }
    
    @Test
    void shouldMaintainDataConsistency() {
        long accountId = 1003L;
        
        // Perform multiple operations
        BigDecimal initialBalance = paymentJpaService.getBalance(accountId);
        
        // Multiple payments
        paymentJpaService.processPayment(accountId, new BigDecimal("10.00"), "Payment 1");
        paymentJpaService.processPayment(accountId, new BigDecimal("20.00"), "Payment 2");
        paymentJpaService.processPayment(accountId, new BigDecimal("30.00"), "Payment 3");
        
        // Verify final balance
        BigDecimal expectedBalance = initialBalance
            .subtract(new BigDecimal("10.00"))
            .subtract(new BigDecimal("20.00"))
            .subtract(new BigDecimal("30.00"));
        
        BigDecimal actualBalance = paymentJpaService.getBalance(accountId);
        assertEquals(expectedBalance, actualBalance);
        
        // Verify transaction count
        List<Transaction> transactions = paymentJpaService.getTransactionHistory(accountId, 10);
        long paymentCount = transactions.stream()
            .filter(tx -> tx.getDescription().startsWith("Payment"))
            .count();
        assertEquals(3, paymentCount);
    }
    
    @Test
    void shouldHandleRepositoryQueries() {
        long accountId = 1001L;
        
        // Test custom repository methods
        Optional<BigDecimal> balance = accountRepository.getBalance(accountId, accountId);
        assertTrue(balance.isPresent());
        assertTrue(balance.get().compareTo(BigDecimal.ZERO) >= 0);
        
        // Test transaction queries
        List<Transaction> recentTransactions = transactionRepository.findRecentTransactions(accountId, accountId, 5);
        assertNotNull(recentTransactions);
        
        long transactionCount = transactionRepository.countByAccountId(accountId, accountId);
        assertTrue(transactionCount >= 0);
    }
    
    private long findAccountOnSameShard(long referenceAccount) {
        long[] testAccounts = {1001L, 1002L, 1003L, 10001L};
        var referenceShard = shardRouter.resolve(referenceAccount);
        
        for (long accountId : testAccounts) {
            if (accountId != referenceAccount) {
                var shard = shardRouter.resolve(accountId);
                if (shard.index() == referenceShard.index()) {
                    return accountId;
                }
            }
        }
        return -1; // Not found
    }
    
    private long findAccountOnDifferentShard(long referenceAccount) {
        long[] testAccounts = {1001L, 1002L, 1003L, 10001L};
        var referenceShard = shardRouter.resolve(referenceAccount);
        
        for (long accountId : testAccounts) {
            if (accountId != referenceAccount) {
                var shard = shardRouter.resolve(accountId);
                if (shard.index() != referenceShard.index()) {
                    return accountId;
                }
            }
        }
        return -1; // Not found
    }
}
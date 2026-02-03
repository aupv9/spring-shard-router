package com.fintech.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.jdbc.ShardJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for JDBC sharding functionality
 */
@SpringBootTest
@Testcontainers
class ShardingJdbcTest {
    
    @Container
    static PostgreSQLContainer<?> postgres0 = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withInitScript("test-schema.sql");
    
    @Container
    static PostgreSQLContainer<?> postgres1 = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withInitScript("test-schema.sql");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("sharding.enabled", () -> "true");
        registry.add("sharding.strategy", () -> "HASH");
        
        registry.add("sharding.shards[0].name", () -> "shard-0");
        registry.add("sharding.shards[0].datasource.jdbc-url", postgres0::getJdbcUrl);
        registry.add("sharding.shards[0].datasource.username", postgres0::getUsername);
        registry.add("sharding.shards[0].datasource.password", postgres0::getPassword);
        
        registry.add("sharding.shards[1].name", () -> "shard-1");
        registry.add("sharding.shards[1].datasource.jdbc-url", postgres1::getJdbcUrl);
        registry.add("sharding.shards[1].datasource.username", postgres1::getUsername);
        registry.add("sharding.shards[1].datasource.password", postgres1::getPassword);
        
        // Test override
        registry.add("sharding.overrides.9999", () -> "0");
        
        // Disable JPA for this test
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }
    
    @Autowired
    private ShardRouter shardRouter;
    
    @Autowired
    private ShardJdbcTemplate shardJdbcTemplate;
    
    @Test
    void shouldRouteToCorrectShard() {
        long accountId1 = 1001L;
        long accountId2 = 1002L;
        
        var shard1 = shardRouter.resolve(accountId1);
        var shard2 = shardRouter.resolve(accountId2);
        
        assertNotNull(shard1);
        assertNotNull(shard2);
        
        assertTrue(shard1.index() >= 0 && shard1.index() < 2);
        assertTrue(shard2.index() >= 0 && shard2.index() < 2);
        
        System.out.println("Account " + accountId1 + " -> " + shard1.name() + " (index: " + shard1.index() + ")");
        System.out.println("Account " + accountId2 + " -> " + shard2.name() + " (index: " + shard2.index() + ")");
    }
    
    @Test
    void shouldRespectOverrides() {
        long overrideAccountId = 9999L;
        var shard = shardRouter.resolve(overrideAccountId);
        
        assertEquals(0, shard.index());
        assertEquals("shard-0", shard.name());
    }
    
    @Test
    void shouldExecuteShardedQueries() {
        long accountId = 1001L;
        
        // Insert test data
        int inserted = shardJdbcTemplate.update(
            accountId,
            "INSERT INTO accounts (account_id, balance, created_at, updated_at) VALUES (?, ?, NOW(), NOW()) ON CONFLICT (account_id) DO NOTHING",
            accountId, new BigDecimal("500.00")
        );
        
        // Query the data
        BigDecimal balance = shardJdbcTemplate.queryForObject(
            accountId,
            "SELECT balance FROM accounts WHERE account_id = ?",
            BigDecimal.class,
            accountId
        );
        
        assertNotNull(balance);
        assertTrue(balance.compareTo(BigDecimal.ZERO) >= 0);
    }
    
    @Test
    void shouldHandleBatchOperations() {
        long accountId = 2001L;
        
        // Prepare batch data
        List<Object[]> batchData = List.of(
            new Object[]{accountId, new BigDecimal("100.00"), "Batch payment 1"},
            new Object[]{accountId, new BigDecimal("200.00"), "Batch payment 2"},
            new Object[]{accountId, new BigDecimal("300.00"), "Batch payment 3"}
        );
        
        // Execute batch insert
        int[] results = shardJdbcTemplate.batchUpdate(
            accountId,
            "INSERT INTO transactions (account_id, amount, description, created_at, status) VALUES (?, ?, ?, NOW(), 'COMPLETED')",
            batchData
        );
        
        assertEquals(3, results.length);
        for (int result : results) {
            assertEquals(1, result); // Each insert should affect 1 row
        }
        
        // Verify data was inserted
        List<Map<String, Object>> transactions = shardJdbcTemplate.queryForList(
            accountId,
            "SELECT * FROM transactions WHERE account_id = ? ORDER BY created_at",
            accountId
        );
        
        assertTrue(transactions.size() >= 3);
    }
    
    @Test
    void shouldMaintainTransactionIsolation() {
        long accountId = 3001L;
        
        // Create account first
        shardJdbcTemplate.update(
            accountId,
            "INSERT INTO accounts (account_id, balance, created_at, updated_at) VALUES (?, ?, NOW(), NOW()) ON CONFLICT (account_id) DO NOTHING",
            accountId, new BigDecimal("1000.00")
        );
        
        // Test that operations on same shard key use same connection/transaction
        BigDecimal initialBalance = shardJdbcTemplate.queryForObject(
            accountId,
            "SELECT balance FROM accounts WHERE account_id = ?",
            BigDecimal.class,
            accountId
        );
        
        // Update balance
        shardJdbcTemplate.update(
            accountId,
            "UPDATE accounts SET balance = balance - ? WHERE account_id = ?",
            new BigDecimal("100.00"), accountId
        );
        
        // Verify update
        BigDecimal newBalance = shardJdbcTemplate.queryForObject(
            accountId,
            "SELECT balance FROM accounts WHERE account_id = ?",
            BigDecimal.class,
            accountId
        );
        
        assertEquals(initialBalance.subtract(new BigDecimal("100.00")), newBalance);
    }
    
    @Test
    void shouldHandleComplexQueries() {
        long accountId = 4001L;
        
        // Create test data
        shardJdbcTemplate.update(
            accountId,
            "INSERT INTO accounts (account_id, balance, created_at, updated_at) VALUES (?, ?, NOW(), NOW()) ON CONFLICT (account_id) DO NOTHING",
            accountId, new BigDecimal("1000.00")
        );
        
        // Insert some transactions
        for (int i = 1; i <= 5; i++) {
            shardJdbcTemplate.update(
                accountId,
                "INSERT INTO transactions (account_id, amount, description, created_at, status) VALUES (?, ?, ?, NOW(), 'COMPLETED')",
                accountId, new BigDecimal(i * 10), "Transaction " + i
            );
        }
        
        // Complex query with aggregation
        Map<String, Object> summary = shardJdbcTemplate.queryForMap(
            accountId,
            """
            SELECT 
                COUNT(*) as transaction_count,
                SUM(amount) as total_amount,
                AVG(amount) as average_amount,
                MAX(amount) as max_amount,
                MIN(amount) as min_amount
            FROM transactions 
            WHERE account_id = ?
            """,
            accountId
        );
        
        assertNotNull(summary);
        assertTrue(((Number) summary.get("transaction_count")).intValue() >= 5);
        assertTrue(((BigDecimal) summary.get("total_amount")).compareTo(BigDecimal.ZERO) > 0);
    }
}
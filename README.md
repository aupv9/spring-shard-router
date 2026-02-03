# Spring Boot Starter Sharding JDBC

🎯 **Finance-grade database sharding solution** cho Spring Boot applications. Plug-and-play, clean code, dễ scale.

[![CI](https://github.com/your-username/spring-boot-starter-sharding-jdbc/workflows/CI/badge.svg)](https://github.com/your-username/spring-boot-starter-sharding-jdbc/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Maven Central](https://img.shields.io/maven-central/v/org.springframework.boot/spring-boot-starter-sharding-jdbc.svg)](https://search.maven.org/artifact/org.springframework.boot/spring-boot-starter-sharding-jdbc)
[![Java Version](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://openjdk.java.net/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2%2B-green.svg)](https://spring.io/projects/spring-boot)

## ✨ Key Features

- ✅ **Zero business logic impact** - App không biết shard logic
- ✅ **Auto-configuration** - Chỉ cần config trong `application.yml`
- ✅ **Finance-grade safety** - Idempotent, transaction-safe
- ✅ **PgBouncer ready** - Optimized cho connection pooling
- ✅ **VIP routing** - Override specific accounts to dedicated shards
- ✅ **Spring Boot standard** - Follows Spring Boot Starter conventions
- ✅ **JPA/Hibernate support** - Shard-aware repositories and entities
- ✅ **JDBC Template support** - For direct SQL operations

## 🚀 Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>sharding-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Configure Shards

```yaml
sharding:
  enabled: true
  strategy: HASH
  shards:
    - name: shard-0
      datasource:
        jdbc-url: jdbc:postgresql://pgbouncer-0:6432/payment_db
        username: payment_user
        password: payment_pass
    - name: shard-1
      datasource:
        jdbc-url: jdbc:postgresql://pgbouncer-1:6432/payment_db
        username: payment_user
        password: payment_pass
  # VIP overrides (optional)
  overrides:
    10001: 0  # VIP account always goes to shard-0
```

### 3. Use in Service (JPA Style)

```java
@Service
public class PaymentService {
    
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    
    @Transactional("shardJpaTransactionManager")
    public void processPayment(long accountId, BigDecimal amount, String description) {
        // Find account - automatically routed to correct shard
        Account account = accountRepository.findByAccountId(accountId, accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        
        // Create transaction
        Transaction tx = new Transaction(accountId, amount, description);
        transactionRepository.save(accountId, tx);
        
        // Update balance
        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(accountId, account);
    }
}
```

### 3. Use in Service (JDBC Style)

```java
@Service
public class PaymentService {
    
    private final ShardJdbcTemplate shardJdbc;
    
    @Transactional("shardTransactionManager")
    public void processPayment(long accountId, BigDecimal amount) {
        // Automatically routed to correct shard based on accountId
        shardJdbc.update(
            accountId,  // shard key
            "INSERT INTO transactions(account_id, amount) VALUES (?, ?)",
            accountId, amount
        );
    }
}
```

## 🏗️ Architecture

```
sharding-spring-boot-starter
├─ sharding-core              # Core routing logic
├─ sharding-jdbc              # JDBC integration  
├─ sharding-jpa               # JPA/Hibernate integration
├─ sharding-autoconfigure     # Spring Boot auto-config
└─ sharding-spring-boot-starter # Starter module
```

### Core Components

- **ShardRouter** - Routes shard keys to target shards
- **ShardJdbcTemplate** - Shard-aware JDBC operations
- **ShardJpaRepository** - Shard-aware JPA repository interface
- **ShardEntityManager** - Shard-aware JPA EntityManager
- **RoutingDataSource** - Dynamic DataSource routing
- **ShardTransactionManager** - Transaction management per shard

## 📦 Modules

### sharding-core
- `ShardRouter` - Core routing interface
- `HashShardStrategy` - Consistent hash-based routing
- `ShardContext` - ThreadLocal shard key management

### sharding-jdbc  
- `ShardJdbcTemplate` - Shard-aware JDBC template
- `RoutingDataSource` - Dynamic DataSource routing
- `ShardTransactionManager` - Per-shard transactions

### sharding-jpa
- `ShardJpaRepository` - Shard-aware JPA repository interface
- `ShardEntityManager` - Shard-aware EntityManager wrapper
- `RoutingEntityManagerFactory` - Dynamic EntityManagerFactory routing
- `@ShardBy` - Annotation for automatic shard key extraction

### sharding-autoconfigure
- `ShardingAutoConfiguration` - Spring Boot auto-configuration
- `ShardProperties` - Configuration properties binding

## 🔧 Configuration Options

```yaml
sharding:
  enabled: true                    # Enable/disable sharding
  strategy: HASH                   # Sharding strategy (HASH only for now)
  shards:                         # List of shard configurations
    - name: shard-0               # Shard identifier
      datasource:                 # DataSource config per shard
        jdbc-url: jdbc:postgresql://...
        username: user
        password: pass
        maximum-pool-size: 20     # HikariCP settings
        minimum-idle: 5
        connection-timeout: 30000
        idle-timeout: 600000
        max-lifetime: 1800000
  overrides:                      # VIP/migration overrides
    10001: 0                      # accountId 10001 -> shard-0
    10002: 1                      # accountId 10002 -> shard-1
```

## 🧪 Usage Examples

### JPA Repository Style

```java
@Repository
public interface AccountRepository extends ShardJpaRepository<Account, Long> {
    
    @Query("SELECT a.balance FROM Account a WHERE a.accountId = :accountId")
    Optional<BigDecimal> getBalance(long shardKey, @Param("accountId") Long accountId);
}

@Service
public class PaymentService {
    
    private final AccountRepository accountRepository;
    
    public BigDecimal getBalance(long accountId) {
        return accountRepository.getBalance(accountId, accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    }
    
    @Transactional("shardJpaTransactionManager")
    public void createAccount(long accountId, BigDecimal initialBalance) {
        Account account = new Account(accountId, initialBalance);
        accountRepository.save(accountId, account);
    }
}
```

### JDBC Template Style

```java
@Service
public class PaymentService {
    
    private final ShardJdbcTemplate shardJdbc;
    
    // Single record insert
    public void createPayment(long accountId, BigDecimal amount) {
        shardJdbc.update(
            accountId,
            "INSERT INTO payments(account_id, amount) VALUES (?, ?)",
            accountId, amount
        );
    }
    
    // Query operations
    public BigDecimal getBalance(long accountId) {
        return shardJdbc.queryForObject(
            accountId,
            "SELECT balance FROM accounts WHERE account_id = ?",
            BigDecimal.class,
            accountId
        );
    }
    
    // Batch operations
    public void batchInsert(long accountId, List<Object[]> batchData) {
        shardJdbc.batchUpdate(
            accountId,
            "INSERT INTO transactions(account_id, amount, desc) VALUES (?, ?, ?)",
            batchData
        );
    }
}
```

### Transaction Management

```java
@Service
public class PaymentService {
    
    @Transactional("shardTransactionManager")
    public void transferMoney(long fromAccount, long toAccount, BigDecimal amount) {
        // Note: Cross-shard transactions not supported
        // Both accounts must be on same shard
        if (!onSameShard(fromAccount, toAccount)) {
            throw new IllegalArgumentException("Cross-shard transfers not supported");
        }
        
        shardJdbc.update(fromAccount, 
            "UPDATE accounts SET balance = balance - ? WHERE account_id = ?",
            amount, fromAccount);
            
        shardJdbc.update(toAccount,
            "UPDATE accounts SET balance = balance + ? WHERE account_id = ?", 
            amount, toAccount);
    }
}
```

## 🚨 Limitations

- ❌ **Cross-shard joins** - Not supported
- ❌ **Cross-shard transactions** - Single shard per transaction only
- ❌ **XA/JTA** - No distributed transaction support

## 🛣️ Roadmap

### v1.0 (Current)
- ✅ Hash-based sharding
- ✅ ShardJdbcTemplate
- ✅ JPA/Hibernate support
- ✅ PgBouncer integration
- ✅ VIP overrides

### v2.0 (Planned)
- 🔄 @ShardBy annotation support
- 🔄 Metrics & monitoring
- 🔄 Read/write splitting
- 🔄 Dynamic shard management

### v3.0 (Future)
- 🔄 CDC integration
- 🔄 Online shard migration
- 🔄 Cross-shard query optimization

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Inspired by Spring Boot's auto-configuration patterns
- Built with ❤️ for the Spring Boot community
- Special thanks to all contributors

## 📞 Support

- 📖 [Documentation](https://github.com/your-username/spring-boot-starter-sharding-jdbc/wiki)
- 🐛 [Issue Tracker](https://github.com/your-username/spring-boot-starter-sharding-jdbc/issues)
- 💬 [Discussions](https://github.com/your-username/spring-boot-starter-sharding-jdbc/discussions)

---

**Made with ❤️ for the Spring Boot community**
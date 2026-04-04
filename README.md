# Spring Boot Starter Sharding JDBC

**Finance-grade database sharding solution** cho Spring Boot applications. Plug-and-play, clean code, dễ scale.

[![CI](https://github.com/aupv9/spring-shard-router/workflows/CI/badge.svg)](https://github.com/aupv9/spring-shard-router/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java Version](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://openjdk.java.net/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2%2B-green.svg)](https://spring.io/projects/spring-boot)

---

## Tính năng

| Tính năng | Trạng thái | Module |
|---|---|---|
| Hash-based sharding (modulo) | ✅ v1.0 | `sharding-core` |
| Consistent hash sharding (virtual nodes) | ✅ v1.0 | `sharding-core` |
| ShardJdbcTemplate (JDBC layer) | ✅ v1.0 | `sharding-jdbc` |
| ShardJpaRepository / ShardEntityManager | ✅ v1.0 | `sharding-jpa` |
| VIP / migration key overrides | ✅ v1.0 | `sharding-core` |
| ThreadLocal context + async propagation | ✅ v1.0 | `sharding-core` |
| Scatter-gather cross-shard queries | ✅ v1.0 | `sharding-jdbc` |
| HikariCP per-shard pools | ✅ v1.0 | `sharding-autoconfigure` |
| **@ShardBy AOP** (automatic shard key extraction) | ✅ v2.0 | `sharding-aop` |
| **Micrometer metrics** (routing, latency, errors) | ✅ v2.0 | `sharding-metrics` |
| **Read/write splitting** (read replicas) | ✅ v2.0 | `sharding-readwrite` |
| **Dynamic shard management** (Actuator API) | ✅ v2.0 | `sharding-autoconfigure` |
| **Shard affinity hints** (query subset of shards) | ✅ v3.0 | `sharding-jdbc` |
| **Cross-shard paged queries** | ✅ v3.0 | `sharding-jdbc` |
| **Online shard migration** (double-write + backfill) | ✅ v3.0 | `sharding-migration` |
| **CDC integration** (Debezium / Kafka) | ✅ v3.0 | `sharding-cdc` |

---

## Quick Start

### 1. Thêm dependency

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>sharding-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

Các module optional — thêm vào khi cần:

```xml
<!-- @ShardBy AOP -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>sharding-aop</artifactId>
    <version>2.0.0</version>
</dependency>

<!-- Micrometer metrics -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>sharding-metrics</artifactId>
    <version>2.0.0</version>
</dependency>

<!-- Read/write splitting -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>sharding-readwrite</artifactId>
    <version>2.0.0</version>
</dependency>

<!-- Online shard migration -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>sharding-migration</artifactId>
    <version>2.0.0</version>
</dependency>

<!-- CDC integration -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>sharding-cdc</artifactId>
    <version>2.0.0</version>
</dependency>
```

### 2. Cấu hình shards

```yaml
sharding:
  enabled: true
  strategy: CONSISTENT_HASH        # HASH | CONSISTENT_HASH
  virtual-nodes-per-shard: 150     # dùng với CONSISTENT_HASH
  shards:
    - name: shard-0
      datasource:
        jdbc-url: jdbc:postgresql://pgbouncer-0:6432/payment_db
        username: payment_user
        password: payment_pass
        maximum-pool-size: 20
    - name: shard-1
      datasource:
        jdbc-url: jdbc:postgresql://pgbouncer-1:6432/payment_db
        username: payment_user
        password: payment_pass
  overrides:
    10001: 0   # VIP account luôn đến shard-0
    10002: 0
```

### 3. Sử dụng trong service

**JDBC style:**
```java
@Service
public class PaymentService {

    private final ShardJdbcTemplate shardJdbc;

    @Transactional("shardTransactionManager")
    public void processPayment(long accountId, BigDecimal amount) {
        shardJdbc.update(
            accountId,
            "UPDATE accounts SET balance = balance - ? WHERE account_id = ?",
            amount, accountId
        );
    }
}
```

**JPA style:**
```java
@Repository
public interface AccountRepository extends ShardJpaRepository<Account, Long> {}

@Service
public class PaymentService {

    private final AccountRepository accountRepository;

    public Account getAccount(long accountId) {
        return accountRepository.findById(accountId, accountId)
            .orElseThrow(() -> new IllegalArgumentException("Not found"));
    }
}
```

---

## Kiến trúc module

```
spring-shard-router/
  sharding-core/           Core: ShardRouter, ShardContext, Shard record
  sharding-jdbc/           JDBC: ShardJdbcTemplate, RoutingDataSource, ScatterGather
  sharding-jpa/            JPA: ShardJpaRepository, ShardEntityManager, @ShardBy
  sharding-aop/            v2.0: @ShardBy AOP interceptor
  sharding-metrics/        v2.0: Micrometer metrics
  sharding-readwrite/      v2.0: Read/write splitting
  sharding-autoconfigure/  Spring Boot auto-configuration cho tất cả modules
  sharding-migration/      v3.0: Online shard migration
  sharding-cdc/            v3.0: CDC integration
  sharding-spring-boot-starter/  Meta-package
  example-app/             Payment service demo
```

---

## Phase 2 — v2.0

### @ShardBy AOP

Loại bỏ boilerplate — không cần truyền `shardKey` thủ công ở mỗi call site.

**Yêu cầu:** thêm `sharding-aop` dependency.

```java
@Service
public class PaymentService {

    private final ShardJdbcTemplate shardJdbc;

    // Mode 1: dùng parameter long/Long đầu tiên làm shard key
    @ShardBy
    public BigDecimal getBalance(long accountId) {
        return shardJdbc.queryForObject(
            ShardContext.get(),   // đã được set bởi aspect
            "SELECT balance FROM accounts WHERE account_id = ?",
            BigDecimal.class, accountId
        );
    }

    // Mode 2: dùng parameter có tên "accountId"
    @ShardBy("accountId")
    public void debit(long accountId, BigDecimal amount) { ... }

    // Mode 3: trích xuất field "accountId" từ entity
    @ShardBy(value = "accountId", fromEntity = true)
    @Transactional("shardTransactionManager")
    public void createPayment(Payment payment) { ... }
}
```

Aspect chạy ở `HIGHEST_PRECEDENCE + 10` — trước `@Transactional`, đảm bảo ShardContext được set trước khi transaction bắt đầu.

---

### Micrometer Metrics

**Yêu cầu:** thêm `sharding-metrics` dependency + Micrometer trên classpath.

Metrics được emit tự động — không cần cấu hình thêm:

| Metric | Type | Tags | Mô tả |
|--------|------|------|--------|
| `sharding.routing.count` | Counter | `shard`, `strategy` | Số routing decisions per shard |
| `sharding.routing.latency` | Timer | `shard` | Thời gian resolve shard key |
| `sharding.query.latency` | Timer | `shard`, `operation` | Thời gian thực thi query |
| `sharding.errors` | Counter | `shard`, `exception` | Số lỗi per shard |
| `sharding.shard.count` | Gauge | — | Tổng số shards đang active |

HikariCP pool metrics được tự động gắn tag `{shard=<name>}` — xem trên Grafana/Prometheus.

---

### Read/Write Splitting

**Yêu cầu:** thêm `sharding-readwrite` dependency.

```yaml
sharding:
  enabled: true
  read-write-splitting:
    enabled: true
  shards:
    - name: shard-0
      datasource:
        jdbc-url: jdbc:postgresql://primary-0:5432/db
        username: user
        password: pass
      read-replicas:
        - jdbc-url: jdbc:postgresql://replica-0a:5432/db
          username: user
          password: pass
        - jdbc-url: jdbc:postgresql://replica-0b:5432/db
          username: user
          password: pass
```

Routing tự động:
- `@Transactional(readOnly=true)` → replica (round-robin)
- `ShardContext.setReadOnly(true)` → replica
- Mặc định → primary

```java
@Service
public class ReportService {

    // Tự động đến replica — không cần thay đổi code
    @Transactional(readOnly = true)
    public List<Account> listAccounts(long accountId) {
        return shardJdbc.query(accountId, "SELECT * FROM accounts", ...);
    }
}
```

Flag `READ_ONLY` cũng được propagate sang `@Async` threads qua `ReplicaAwareShardContextTaskDecorator`.

---

### Dynamic Shard Management

**Yêu cầu:** `sharding.management.enabled=true` + `spring-boot-starter-actuator`.

```yaml
sharding:
  management:
    enabled: true
management:
  endpoints:
    web:
      exposure:
        include: shards
```

**Actuator endpoint `/actuator/shards`:**

```bash
# Liệt kê tất cả shards
GET /actuator/shards

# Thêm shard mới lúc runtime (không cần restart)
POST /actuator/shards
{
  "name": "shard-3",
  "jdbcUrl": "jdbc:postgresql://new-host:5432/db",
  "username": "user",
  "password": "pass"
}

# Xóa shard
DELETE /actuator/shards?name=shard-3
```

Hoặc dùng `ShardManagementService` trực tiếp trong code:

```java
@Autowired
ShardManagementService shardManagement;

// Thêm shard
ShardProperties.ShardConfig config = new ShardProperties.ShardConfig();
config.setName("shard-3");
// ... set datasource config
shardManagement.addShard(config);

// Thêm override
shardManagement.addOverride(99999L, 0);  // route key 99999 -> shard-0
```

> **Lưu ý:** Chỉ `ConsistentHashShardRouter` hỗ trợ add/remove shard. `HashShardRouter` sẽ throw `UnsupportedOperationException`.

---

## Phase 3 — v3.0

### Cross-Shard Query Optimization

#### Shard Affinity — Query subset of shards

Khi biết dữ liệu nằm ở shards nào, tránh query tất cả shards:

```java
@Autowired
ShardScatterGatherTemplate scatterGather;

// Query chỉ shards 0 và 1 (thay vì tất cả shards)
List<Account> accounts = scatterGather.queryShards(
    List.of(0, 1),
    "SELECT * FROM accounts WHERE region = ?",
    (rs, row) -> mapAccount(rs),
    "APAC"
);
```

#### Cross-Shard Paged Queries

Pagination đúng nghĩa trên tất cả shards — push `LIMIT` xuống từng shard, merge + sort + paginate ở application layer:

```java
// Page 2, 20 rows/page, sort theo created_at DESC
List<Transaction> page = scatterGather.queryAllShardsPaged(
    "SELECT * FROM transactions WHERE status = ?",
    (rs, row) -> mapTransaction(rs),
    Comparator.comparing(Transaction::getCreatedAt).reversed(),
    20,   // pageSize
    1,    // pageNumber (0-based)
    "COMPLETED"
);
```

Hiệu quả hơn full scatter-gather: mỗi shard trả về `(pageNumber + 1) * pageSize` rows thay vì toàn bộ table.

---

### Online Shard Migration

**Yêu cầu:** `sharding.migration.enabled=true`.

Migration diễn ra 3 phase — không downtime:

```
PENDING → DOUBLE_WRITING → BACKFILLING → READY_TO_CUTOVER → COMPLETED
                                                           → ROLLED_BACK
```

**Kích hoạt qua Actuator:**

```bash
# Bắt đầu migration: key range 1000–1999 từ shard-0 sang shard-1
POST /actuator/shard-migration
{
  "keyMin": 1000,
  "keyMax": 1999,
  "sourceShard": 0,
  "targetShard": 1,
  "backfillBatchSize": 500
}

# Kiểm tra tiến trình
GET /actuator/shard-migration
# Response: { "state": "BACKFILLING", "rowsCopied": 3200, "progressPercent": 64.0 }

# Cutover khi backfill xong
DELETE /actuator/shard-migration/{migrationId}?action=cutover

# Rollback nếu cần
DELETE /actuator/shard-migration/{migrationId}?action=rollback
```

**Hoặc dùng `ShardMigrationService` trong code:**

```java
@Autowired ShardMigrationService migrationService;

// Phase 1: bắt đầu double-write
ShardMigrationPlan plan = migrationService.startMigration(
    1000L, 1999L, 0, 1, 500);

// Phase 2: backfill (chạy async/ngoài giờ)
migrationService.runBackfill(plan.migrationId(), "accounts", "account_id", "id");

// Phase 3: cutover khi sẵn sàng
migrationService.cutover(plan.migrationId());
```

**Double-write tự động qua `@ShardBy`:**

```yaml
sharding:
  migration:
    enabled: true
    double-write-enabled: true   # bật khi có active migration
```

Khi `double-write-enabled=true`, `ShardMigrationAspect` interceptor tự động ghi đồng thời vào source lẫn target shard cho mọi method annotated `@ShardBy` trong migration range.

---

### CDC Integration (Change Data Capture)

**Yêu cầu:** `sharding.cdc.enabled=true` + Debezium hoặc Kafka connector.

**Với Debezium embedded:**

```xml
<dependency>
    <groupId>io.debezium</groupId>
    <artifactId>debezium-connector-postgres</artifactId>
    <version>2.4.0.Final</version>
</dependency>
```

```java
@Bean
public DebeziumShardChangeProducer cdcProducer(
        List<ShardChangeEventListener> listeners) {
    Properties config = new Properties();
    config.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
    config.setProperty("database.hostname", "shard-0-host");
    config.setProperty("database.port", "5432");
    config.setProperty("database.user", "replication_user");
    config.setProperty("database.password", "...");
    config.setProperty("database.dbname", "payment_db");
    config.setProperty("database.server.name", "shard-0");

    return new DebeziumShardChangeProducer("shard-0", config, "account_id", listeners);
}
```

**Với Kafka:**

```yaml
sharding:
  cdc:
    enabled: true
    type: kafka
```

```java
@Bean
public KafkaShardChangeProducer kafkaCdcProducer(
        List<ShardChangeEventListener> listeners) {
    Properties kafkaConfig = new Properties();
    kafkaConfig.setProperty("bootstrap.servers", "kafka:9092");
    kafkaConfig.setProperty("group.id", "shard-cdc-consumer");
    kafkaConfig.setProperty("key.deserializer", StringDeserializer.class.getName());
    kafkaConfig.setProperty("value.deserializer", StringDeserializer.class.getName());

    return new KafkaShardChangeProducer(
        "shard-0", kafkaConfig,
        List.of("sharding.changes.public.accounts"),
        listeners
    );
}
```

**Custom listener:**

```java
@Component
public class AuditCdcListener implements ShardChangeEventListener {

    @Override
    public void onShardChange(ShardChangeEvent event) {
        // event.shardName(), event.tableName(), event.operation(), event.shardKey()
        auditLog.record(event);
    }
}
```

**Tích hợp Cache Invalidation:**

```java
@Component
public class MyCacheInvalidator extends ShardCacheInvalidator {

    private final CacheManager cacheManager;

    @Override
    protected void invalidate(ShardChangeEvent event) {
        cacheManager.getCache("scatter-gather-" + event.tableName()).clear();
    }
}
```

---

## Cấu hình đầy đủ

```yaml
sharding:
  enabled: true
  strategy: CONSISTENT_HASH          # HASH | CONSISTENT_HASH
  virtual-nodes-per-shard: 150

  shards:
    - name: shard-0
      datasource:
        jdbc-url: jdbc:postgresql://primary-0:5432/db
        username: user
        password: pass
        driver-class-name: org.postgresql.Driver
        maximum-pool-size: 20
        minimum-idle: 5
        connection-timeout: 30000
        idle-timeout: 600000
        max-lifetime: 1800000
      read-replicas:
        - jdbc-url: jdbc:postgresql://replica-0:5432/db
          username: user
          password: pass
    - name: shard-1
      datasource:
        jdbc-url: jdbc:postgresql://primary-1:5432/db
        username: user
        password: pass

  overrides:
    10001: 0    # VIP account -> shard-0
    10002: 0

  # v2.0: Read/write splitting
  read-write-splitting:
    enabled: true

  # v2.0: Dynamic management Actuator endpoint
  management:
    enabled: false   # bật trong controlled environments

  # v3.0: Online migration
  migration:
    enabled: false
    double-write-enabled: false

  # v3.0: CDC
  cdc:
    enabled: false
```

---

## Transaction Management

Chỉ hỗ trợ single-shard transactions. Cross-shard transaction không được hỗ trợ (by design).

```java
// JDBC transactions
@Transactional("shardTransactionManager")
public void processPayment(long accountId, BigDecimal amount) {
    shardJdbc.update(accountId, "UPDATE accounts SET balance = balance - ? ...", amount);
    shardJdbc.update(accountId, "INSERT INTO transactions (...) VALUES (...)", ...);
}

// JPA transactions
@Transactional("shardJpaTransactionManager")
public void createAccount(long accountId, BigDecimal balance) {
    Account account = new Account(accountId, balance);
    accountRepository.save(accountId, account);
}

// Cross-shard phải check trước
public void transfer(long from, long to, BigDecimal amount) {
    if (shardRouter.resolve(from).index() != shardRouter.resolve(to).index()) {
        throw new IllegalArgumentException("Cross-shard transfers not supported");
    }
    // ... proceed
}
```

---

## Async Context Propagation

ShardContext tự động được propagate sang `@Async` threads:

```java
// Đăng ký decorator với thread pool executor
@Bean
public ThreadPoolTaskExecutor taskExecutor(ReplicaAwareShardContextTaskDecorator decorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(decorator);    // propagate shardKey + readOnly flag
    executor.initialize();
    return executor;
}

// Shard key được set tự động trong async method
@Async
public CompletableFuture<List<Transaction>> getHistoryAsync(long accountId) {
    // ShardContext.get() == accountId — propagated từ calling thread
    return CompletableFuture.completedFuture(
        shardJdbc.query(accountId, "SELECT * FROM transactions ...", ...)
    );
}
```

---

## Modules

| Module | Phụ thuộc | Kích hoạt |
|--------|-----------|-----------|
| `sharding-core` | — | Luôn active |
| `sharding-jdbc` | `sharding-core` | Luôn active |
| `sharding-jpa` | `sharding-jdbc` | Luôn active |
| `sharding-aop` | `sharding-core`, `spring-aop` | Khi có dependency + `sharding.enabled=true` |
| `sharding-metrics` | `sharding-jdbc`, `micrometer-core` | Khi `MeterRegistry` bean có mặt |
| `sharding-readwrite` | `sharding-core` | Khi `sharding.read-write-splitting.enabled=true` |
| `sharding-migration` | `sharding-jdbc`, `sharding-aop` | Khi `sharding.migration.enabled=true` |
| `sharding-cdc` | `sharding-core` | Khi `sharding.cdc.enabled=true` |

---

## Limitations

- **Cross-shard transactions** không được hỗ trợ — single-shard only
- **Cross-shard joins** không được hỗ trợ — dùng scatter-gather để merge ở application layer
- **HashShardRouter** không hỗ trợ dynamic shard add/remove — dùng `ConsistentHashShardRouter`
- **Scatter-gather** query tất cả shards song song — chi phí tăng tuyến tính theo số shards, chỉ dùng cho admin/reporting
- **Online migration** state hiện tại lưu in-memory — multi-node deployments cần shared store (Redis, DB)

---

## Roadmap

| Version | Status | Highlights |
|---------|--------|------------|
| v1.0 | ✅ Released (2026-02-03) | Hash sharding, JDBC/JPA, HikariCP, VIP overrides, Testcontainers tests |
| v2.0 | ✅ Released (2026-04-04) | @ShardBy AOP, Micrometer metrics, Read/write splitting, Dynamic management |
| v3.0 | ✅ Released (2026-04-04) | Cross-shard pagination, Online migration, CDC (Debezium/Kafka) |

---

## Contributing

1. Fork repository
2. Tạo feature branch: `git checkout -b feature/amazing-feature`
3. Commit: `git commit -m 'feat: add amazing feature'`
4. Push: `git push origin feature/amazing-feature`
5. Mở Pull Request

Xem [CONTRIBUTING.md](CONTRIBUTING.md) để biết thêm chi tiết.

## License

MIT License — xem [LICENSE](LICENSE).

---

*Built with for the Spring Boot community*

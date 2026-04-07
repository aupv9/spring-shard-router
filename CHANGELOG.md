# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [4.0.0] - 2026-04-06

### Added — Phase 4: Production Hardening & Distributed Transactions

#### Cross-Shard Saga Orchestration (sharding-saga — new module)
- `ShardSagaStep<T>` — interface defining `execute(T)` + `compensate(T)` for one saga step
- `ShardSagaDefinition<T>` — immutable ordered list of steps; built via fluent `builder()` API
- `ShardSagaOrchestrator` — executes steps in order; on failure compensates completed steps in reverse; continues even if individual compensation steps throw
- `ShardSagaLog` — immutable audit record (sagaId, stepName, Phase, Status, timestamp, errorMsg)
- `ShardSagaException` — carries sagaId + full audit log; thrown after compensation completes
- `ShardingSagaAutoConfiguration` — enabled via `sharding.saga.enabled=true`
- Registered in `sharding-spring-boot-starter` auto-configuration imports

#### Virtual Thread Executor (sharding-core)
- `VirtualThreadShardExecutor` — factory producing an `ExecutorService` optimised per JVM:
  - JDK 21+: `Executors.newVirtualThreadPerTaskExecutor()` via reflection (stays source-compatible with JDK 17)
  - JDK 17–20: fallback to a cached daemon thread pool
- `VirtualThreadShardExecutor.isVirtualThreadsAvailable()` — runtime JVM version check

#### Test Coverage (filling Phase 2 & 3 gaps)
- `ShardKeyExtractorTest` (sharding-aop) — all three extraction modes: first-long-param, named-param, entity-field; error paths for missing param/field
- `MeteredShardRouterTest` (sharding-metrics) — counter increment, timer recording, multi-call accumulation, and delegation to underlying router
- `ReadWriteRoutingDataSourceTest` (sharding-readwrite) — primary routing for writes, replica routing for reads, round-robin replica selection, no-replica fallback
- `ShardMigrationServiceTest` (sharding-migration) — state machine transitions, key-range matching, invalid-input validation, getPlan/listPlans/getProgress queries
- `ShardSagaOrchestratorTest` (sharding-saga) — all-steps-success, partial failure + compensation, first-step failure, full audit log assertions

## [3.0.0] - 2026-04-04

### Added — Phase 3

#### Cross-Shard Query Optimization (sharding-jdbc)
- `ShardScatterGatherTemplate.queryShards(List<Integer> shardIndices, ...)` — shard affinity hints, query chỉ subset shards thay vì tất cả
- `ShardScatterGatherTemplate.queryAllShardsPaged(sql, rowMapper, comparator, pageSize, pageNumber, args)` — cross-shard pagination với LIMIT push-down xuống từng shard

#### Online Shard Migration (sharding-migration — module mới)
- `ShardMigrationPlan` record với state machine đầy đủ: `PENDING → DOUBLE_WRITING → BACKFILLING → READY_TO_CUTOVER → COMPLETED / ROLLED_BACK`
- `ShardMigrationProgressTracker` — thread-safe theo dõi rows copied + progress %
- `ShardMigrationService` — orchestration: start, runBackfill, cutover, rollback
- `ShardMigrationAspect` — double-write interceptor cho `@ShardBy`-annotated write methods
- `ShardMigrationActuatorEndpoint` — `/actuator/shard-migration` (list/start/cutover/rollback)
- `ShardingMigrationAutoConfiguration` — bật qua `sharding.migration.enabled=true`

#### CDC Integration (sharding-cdc — module mới)
- `ShardChangeEvent` record — shardName, tableName, operation, shardKey, payload, timestamp
- `ShardChangeEventListener` interface — listener cho change events
- `DebeziumShardChangeProducer` — embedded Debezium engine → `ShardChangeEvent`
- `KafkaShardChangeProducer` — Kafka consumer (Debezium Kafka Connect topics) → `ShardChangeEvent`
- `ShardMigrationConsistencyChecker` — phát hiện double-write gaps trong migration
- `ShardCacheInvalidator` — base class cho cache eviction khi có write events
- `ShardingCdcAutoConfiguration` — bật qua `sharding.cdc.enabled=true`

## [2.0.0] - 2026-04-04

### Added — Phase 2

#### @ShardBy AOP (sharding-aop — module mới)
- `ShardByAspect` — `@Around` aspect tự động set/clear `ShardContext`, 3 extraction modes: first-long-param, named-param, entity-field (`fromEntity=true`)
- `ShardKeyExtractor` — utility tách biệt logic extraction, dễ unit test
- `ShardKeyExtractionException` — thrown khi không resolve được shard key
- `ShardingAopAutoConfiguration` — `@ConditionalOnClass(Aspect.class)`, bật khi `sharding-aop` có trên classpath
- Aspect order = `HIGHEST_PRECEDENCE + 10`, chạy trước `@Transactional`

#### Micrometer Metrics (sharding-metrics — module mới)
- `MeteredShardRouter` — decorator phát counter `sharding.routing.count` và timer `sharding.routing.latency`
- `MeteredShardJdbcTemplate` — subclass phát timer `sharding.query.latency` và counter `sharding.errors`
- `ShardActuatorMetrics` — `MeterBinder` gauge `sharding.shard.count`
- `ShardingMetricsAutoConfiguration` — `@ConditionalOnBean(MeterRegistry.class)`, auto-wraps router + template
- HikariCP pool metrics qua `hikariConfig.setMetricRegistry(meterRegistry)`

#### Read/Write Splitting (sharding-readwrite — module mới)
- `ReadWriteRoutingDataSource` — route `SELECT` đến read replica, write đến primary; round-robin load balance qua `AtomicInteger` per shard
- `ReplicaAwareShardContextTaskDecorator` — propagate cả `shardKey` lẫn `readOnly` flag sang `@Async` threads
- `ShardingReadWriteAutoConfiguration` — bật qua `sharding.read-write-splitting.enabled=true`
- Tích hợp tự động với `@Transactional(readOnly=true)` qua `TransactionSynchronizationManager`

#### Dynamic Shard Management
- `ShardManagementService` — runtime add/remove/list/override với HikariCP pool lifecycle
- `ShardManagementEndpoint` — Actuator `@Endpoint(id="shards")` cho GET/POST/DELETE operations
- `ShardingManagementAutoConfiguration` — bật qua `sharding.management.enabled=true`

### Changed
- `ShardContext` — thêm `READ_ONLY ThreadLocal`: `setReadOnly()`, `isReadOnly()`, `clearReadOnly()`; `clear()` clear cả 2 thread locals
- `Shard` record — thêm `readReplicaDataSources` field; factory `Shard.withReplicas()`; backward-compatible qua `Shard.of()`
- `ShardRouter` interface — thêm `default void addShard(Shard)` và `default void removeShard(int)` (throw `UnsupportedOperationException` by default)
- `ConsistentHashShardRouter` — `List<Shard>` → `AtomicReference<List<Shard>>` + `ReentrantReadWriteLock`; incremental ring mutation thay vì rebuild
- `ShardJdbcTemplate.executeWithShardKey` — `private` → `protected`, nhận thêm `String operation` parameter để subclass instrumentation
- `ShardBy` annotation — xóa `ElementType.PARAMETER` (incompatible với Spring AOP CGLIB proxies)
- `ShardingAutoConfiguration.createShards` — build replica HikariCP pools khi `readReplicas` được cấu hình
- `ShardProperties.ShardConfig` — thêm `readReplicas: List<DataSourceConfig>`
- `ShardProperties` — thêm `ReadWriteConfig` và `ManagementConfig` inner classes

## [1.0.0] - 2026-02-03

### Added
- Initial release of Spring Boot Starter Sharding JDBC
- Hash-based sharding strategy with Murmur3 hash
- Shard-aware JDBC template (`ShardJdbcTemplate`)
- Shard-aware JPA repository interface (`ShardJpaRepository`)
- Spring Boot auto-configuration support
- VIP account override routing
- ThreadLocal shard context management
- HikariCP connection pooling per shard
- PostgreSQL support with PgBouncer integration
- Comprehensive test suite with Testcontainers
- Performance and concurrency testing
- Finance-grade transaction safety

### Features
- ✅ Zero business logic impact - Apps don't need to know about sharding
- ✅ Auto-configuration - Just configure in `application.yml`
- ✅ Finance-grade safety - Idempotent, transaction-safe operations
- ✅ PgBouncer ready - Optimized for connection pooling
- ✅ VIP routing - Override specific accounts to dedicated shards
- ✅ Spring Boot standard - Follows Spring Boot Starter conventions
- ✅ JPA/Hibernate support - Shard-aware repositories and entities
- ✅ JDBC Template support - For direct SQL operations

### Technical Details
- Java 17+ support
- Spring Boot 3.2+ compatibility
- Maven multi-module project structure
- Comprehensive unit and integration tests
- CI/CD pipeline with GitHub Actions
- Docker-based testing with Testcontainers

### Documentation
- Complete README with usage examples
- API documentation
- Configuration reference
- Performance testing results
- Contributing guidelines

[Unreleased]: https://github.com/your-username/spring-boot-starter-sharding-jdbc/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/your-username/spring-boot-starter-sharding-jdbc/releases/tag/v1.0.0
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
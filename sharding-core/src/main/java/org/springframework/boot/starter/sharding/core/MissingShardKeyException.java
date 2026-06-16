package org.springframework.boot.starter.sharding.core;

/**
 * Thrown by routing DataSources when a database connection is requested but no
 * shard key is present in {@link ShardContext} and the startup fallback has been
 * disabled via {@link ShardContext#disableFallback()}.
 *
 * <p>This exception surfaces bugs where application code executes a SQL operation
 * without first routing to a shard via {@link ShardContext#set},
 * {@code ShardJdbcTemplate}, or {@code @ShardBy}.
 *
 * <p>During Spring Boot startup the fallback is kept enabled so that Hibernate
 * schema validation and HikariCP connection probing can proceed normally. Once the
 * application context is fully started ({@code ApplicationReadyEvent}), the fallback
 * is disabled and any unkeyed access will produce this exception rather than silently
 * reading from or writing to shard-0.
 */
public class MissingShardKeyException extends RuntimeException {

    public MissingShardKeyException() {
        super(
            "No shard key found in ShardContext. "
            + "Every database operation must be routed to a shard via one of: "
            + "(1) ShardJdbcTemplate.query/update(shardKey, ...), "
            + "(2) @ShardBy annotation on the service method, or "
            + "(3) ShardContext.set(shardKey) before executing SQL."
        );
    }
}

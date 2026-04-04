package org.springframework.boot.starter.sharding.metrics;

/**
 * Metric name constants for Spring Shard Router.
 *
 * <p>All metrics use the {@code sharding.} prefix for easy namespacing in dashboards.
 */
public final class ShardMetricsConstants {

    private ShardMetricsConstants() {}

    /** Counter — incremented on every routing decision. Tags: {@code shard}, {@code strategy}. */
    public static final String ROUTING_COUNT = "sharding.routing.count";

    /** Timer — measures time spent in {@link org.springframework.boot.starter.sharding.core.ShardRouter#resolve}. Tags: {@code shard}. */
    public static final String ROUTING_LATENCY = "sharding.routing.latency";

    /** Timer — measures total time for shard-scoped JDBC/JPA operations. Tags: {@code shard}, {@code operation}. */
    public static final String QUERY_LATENCY = "sharding.query.latency";

    /** Counter — incremented whenever a shard operation throws an exception. Tags: {@code shard}, {@code exception}. */
    public static final String ERROR_COUNT = "sharding.errors";

    /** Gauge — current number of configured shards. */
    public static final String SHARD_COUNT = "sharding.shard.count";

    /** Gauge — number of active key overrides. */
    public static final String OVERRIDE_COUNT = "sharding.override.count";

    // Tag keys

    public static final String TAG_SHARD = "shard";
    public static final String TAG_STRATEGY = "strategy";
    public static final String TAG_OPERATION = "operation";
    public static final String TAG_EXCEPTION = "exception";
}

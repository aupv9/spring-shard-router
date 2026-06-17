package org.springframework.boot.starter.sharding.cdc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JDBC-polling CDC source.
 *
 * <p>Periodically runs {@code SELECT * FROM <table> WHERE updated_at > <lastPollTime>}
 * on a single shard and emits one {@link ShardCdcEvent} per returned row.
 *
 * <h2>Tradeoffs vs. Debezium/WAL-based CDC</h2>
 * <table border="1">
 *   <tr><th></th><th>Polling (this class)</th><th>Debezium/WAL</th></tr>
 *   <tr><td>Dependencies</td><td>None (plain JDBC)</td><td>debezium-embedded + DB config</td></tr>
 *   <tr><td>Latency</td><td>Polling interval (seconds)</td><td>Near-realtime (ms)</td></tr>
 *   <tr><td>Deletes</td><td>Cannot detect (row is gone)</td><td>Captured via WAL</td></tr>
 *   <tr><td>Schema changes</td><td>Transparent</td><td>Requires connector restart</td></tr>
 *   <tr><td>DB permissions</td><td>SELECT only</td><td>REPLICATION role required</td></tr>
 * </table>
 *
 * <p><b>Requirements on the watched table:</b> must have an {@code updated_at}
 * timestamp column that is kept current by the application or a DB trigger.
 *
 * <p>Because DELETES are invisible to polling, this source is best used for
 * audit/replication scenarios where rows are soft-deleted (flagged, not removed).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PollingShardCdcSource source = PollingShardCdcSource.builder()
 *     .shardIndex(0)
 *     .dataSource(shard0DataSource)
 *     .table("transactions")
 *     .shardKeyColumn("account_id")
 *     .pollIntervalSeconds(5)
 *     .build();
 * }</pre>
 */
public class PollingShardCdcSource implements ShardCdcSource {

    private static final Logger log = LoggerFactory.getLogger(PollingShardCdcSource.class);

    private final int        shardIndex;
    private final JdbcTemplate jdbc;
    private final String     table;
    private final String     shardKeyColumn;
    private final long       pollIntervalSeconds;

    private Instant lastPollTime = Instant.EPOCH;

    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> pollTask;

    private PollingShardCdcSource(Builder b) {
        this.shardIndex          = b.shardIndex;
        this.jdbc                = new JdbcTemplate(b.dataSource);
        this.table               = b.table;
        this.shardKeyColumn      = b.shardKeyColumn;
        this.pollIntervalSeconds = b.pollIntervalSeconds > 0 ? b.pollIntervalSeconds : 5;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shard-cdc-poll-" + shardIndex);
            t.setDaemon(true);
            return t;
        });
    }

    // -------------------------------------------------------------------------
    // ShardCdcSource
    // -------------------------------------------------------------------------

    @Override
    public void start(ShardCdcDispatcher dispatcher) {
        log.info("[cdc-poll] starting: shard={} table={} interval={}s",
            shardIndex, table, pollIntervalSeconds);
        lastPollTime = Instant.now();
        pollTask = scheduler.scheduleAtFixedRate(
            () -> poll(dispatcher), 0, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        log.info("[cdc-poll] stopping: shard={} table={}", shardIndex, table);
        if (pollTask != null) pollTask.cancel(false);
        scheduler.shutdown();
    }

    @Override
    public String name() {
        return "polling-shard-" + shardIndex + "-" + table;
    }

    // -------------------------------------------------------------------------
    // Polling logic
    // -------------------------------------------------------------------------

    private void poll(ShardCdcDispatcher dispatcher) {
        Instant pollStart = Instant.now();
        try {
            // SELECT rows changed since last poll
            // updated_at > ? to avoid re-emitting the same row multiple times
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE updated_at > ? ORDER BY updated_at ASC",
                java.sql.Timestamp.from(lastPollTime));

            if (!rows.isEmpty()) {
                log.debug("[cdc-poll] shard={} table={} found {} changed rows", shardIndex, table, rows.size());
                for (Map<String, Object> row : rows) {
                    Long shardKey = extractShardKey(row);
                    // Polling cannot distinguish INSERT from UPDATE — report as INSERT
                    // (the listener can consult its own state if the distinction matters)
                    ShardCdcEvent event = new ShardCdcEvent(
                        shardIndex, table, ShardCdcEvent.Operation.INSERT,
                        null, row, shardKey);
                    dispatcher.dispatch(event);
                }
            }

            lastPollTime = pollStart; // advance window only on successful poll
        } catch (Exception ex) {
            log.error("[cdc-poll] poll failed on shard={} table={}: {}", shardIndex, table, ex.getMessage(), ex);
            // lastPollTime is NOT advanced — next poll will re-scan the same window
        }
    }

    private Long extractShardKey(Map<String, Object> row) {
        Object value = row.get(shardKeyColumn);
        if (value instanceof Long l)   return l;
        if (value instanceof Number n) return n.longValue();
        return null;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int        shardIndex;
        private DataSource dataSource;
        private String     table;
        private String     shardKeyColumn;
        private long       pollIntervalSeconds = 5;

        public Builder shardIndex(int idx)              { this.shardIndex = idx;              return this; }
        public Builder dataSource(DataSource ds)        { this.dataSource = ds;               return this; }
        public Builder table(String t)                  { this.table = t;                     return this; }
        public Builder shardKeyColumn(String col)       { this.shardKeyColumn = col;          return this; }
        public Builder pollIntervalSeconds(long secs)   { this.pollIntervalSeconds = secs;    return this; }
        public PollingShardCdcSource build()            { return new PollingShardCdcSource(this); }
    }
}

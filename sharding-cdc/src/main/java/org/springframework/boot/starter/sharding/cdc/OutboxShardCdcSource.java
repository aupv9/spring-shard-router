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
 * Transactional Outbox CDC source.
 *
 * <p>Reads change records from a dedicated <em>outbox table</em> that application
 * code writes atomically alongside its domain data (same DB transaction, same shard).
 * This is the most reliable CDC pattern: events are guaranteed not to be lost even if
 * the application crashes between writing data and emitting an event.
 *
 * <h2>Required outbox table schema</h2>
 * <pre>{@code
 * CREATE TABLE shard_outbox (
 *     id              BIGSERIAL PRIMARY KEY,
 *     table_name      VARCHAR(128)  NOT NULL,
 *     operation       VARCHAR(10)   NOT NULL,  -- INSERT, UPDATE, DELETE
 *     shard_key       BIGINT,
 *     payload         JSONB         NOT NULL,
 *     created_at      TIMESTAMP     NOT NULL DEFAULT now(),
 *     processed       BOOLEAN       NOT NULL DEFAULT FALSE
 * );
 * CREATE INDEX idx_shard_outbox_unprocessed ON shard_outbox (processed, id) WHERE NOT processed;
 * }</pre>
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Application inserts into {@code shard_outbox} inside the same transaction
 *       as the domain write.</li>
 *   <li>This source polls for rows where {@code processed = FALSE}, ordered by {@code id}.</li>
 *   <li>For each row: dispatch a {@link ShardCdcEvent}, then mark the row
 *       {@code processed = TRUE}.</li>
 *   <li>If dispatch fails the row is left unprocessed and will be retried on the
 *       next poll — at-least-once delivery.</li>
 * </ol>
 *
 * <h2>Tradeoffs vs. {@link PollingShardCdcSource}</h2>
 * <ul>
 *   <li>Captures DELETEs (the application records the delete in the outbox).</li>
 *   <li>Requires application code to write to the outbox table.</li>
 *   <li>Outbox rows accumulate and need periodic cleanup.</li>
 * </ul>
 */
public class OutboxShardCdcSource implements ShardCdcSource {

    private static final Logger log = LoggerFactory.getLogger(OutboxShardCdcSource.class);

    /** Default outbox table name (can be overridden via {@link Builder#outboxTable}). */
    public static final String DEFAULT_OUTBOX_TABLE = "shard_outbox";

    private final int          shardIndex;
    private final JdbcTemplate jdbc;
    private final String       outboxTable;
    private final int          batchSize;
    private final long         pollIntervalSeconds;

    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> pollTask;

    private OutboxShardCdcSource(Builder b) {
        this.shardIndex          = b.shardIndex;
        this.jdbc                = new JdbcTemplate(b.dataSource);
        this.outboxTable         = b.outboxTable;
        this.batchSize           = b.batchSize > 0 ? b.batchSize : 100;
        this.pollIntervalSeconds = b.pollIntervalSeconds > 0 ? b.pollIntervalSeconds : 2;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shard-cdc-outbox-" + shardIndex);
            t.setDaemon(true);
            return t;
        });
    }

    // -------------------------------------------------------------------------
    // ShardCdcSource
    // -------------------------------------------------------------------------

    @Override
    public void start(ShardCdcDispatcher dispatcher) {
        log.info("[cdc-outbox] starting: shard={} table={} interval={}s",
            shardIndex, outboxTable, pollIntervalSeconds);
        pollTask = scheduler.scheduleAtFixedRate(
            () -> poll(dispatcher), 0, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        log.info("[cdc-outbox] stopping: shard={}", shardIndex);
        if (pollTask != null) pollTask.cancel(false);
        scheduler.shutdown();
    }

    @Override
    public String name() {
        return "outbox-shard-" + shardIndex;
    }

    // -------------------------------------------------------------------------
    // Poll loop
    // -------------------------------------------------------------------------

    private void poll(ShardCdcDispatcher dispatcher) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + outboxTable
                    + " WHERE processed = FALSE ORDER BY id ASC LIMIT " + batchSize);

            if (rows.isEmpty()) return;

            log.debug("[cdc-outbox] shard={} processing {} outbox rows", shardIndex, rows.size());

            for (Map<String, Object> row : rows) {
                long   outboxId   = ((Number) row.get("id")).longValue();
                String tableName  = (String) row.get("table_name");
                String opStr      = (String) row.get("operation");
                Long   shardKey   = row.get("shard_key") instanceof Number n ? n.longValue() : null;
                Object payload    = row.get("payload");   // JSONB returned as String by JDBC

                ShardCdcEvent.Operation operation;
                try {
                    operation = ShardCdcEvent.Operation.valueOf(opStr.toUpperCase());
                } catch (IllegalArgumentException ex) {
                    log.warn("[cdc-outbox] unknown operation '{}' in outbox row id={} — skipping", opStr, outboxId);
                    markProcessed(outboxId);
                    continue;
                }

                // Wrap payload as an after-image map for INSERT/UPDATE, before-image for DELETE
                Map<String, Object> after  = operation != ShardCdcEvent.Operation.DELETE
                    ? Map.of("payload", payload) : null;
                Map<String, Object> before = operation == ShardCdcEvent.Operation.DELETE
                    ? Map.of("payload", payload) : null;

                ShardCdcEvent event = new ShardCdcEvent(
                    shardIndex, tableName, operation, before, after, shardKey);

                try {
                    dispatcher.dispatch(event);
                    markProcessed(outboxId);
                } catch (Exception ex) {
                    // Leave unprocessed for retry on next poll
                    log.error("[cdc-outbox] dispatch failed for outbox id={}: {}", outboxId, ex.getMessage(), ex);
                }
            }
        } catch (Exception ex) {
            log.error("[cdc-outbox] poll failed on shard={}: {}", shardIndex, ex.getMessage(), ex);
        }
    }

    private void markProcessed(long outboxId) {
        jdbc.update("UPDATE " + outboxTable + " SET processed = TRUE WHERE id = ?", outboxId);
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int        shardIndex;
        private DataSource dataSource;
        private String     outboxTable         = DEFAULT_OUTBOX_TABLE;
        private int        batchSize           = 100;
        private long       pollIntervalSeconds = 2;

        public Builder shardIndex(int idx)              { this.shardIndex = idx;              return this; }
        public Builder dataSource(DataSource ds)        { this.dataSource = ds;               return this; }
        public Builder outboxTable(String t)            { this.outboxTable = t;               return this; }
        public Builder batchSize(int size)              { this.batchSize = size;              return this; }
        public Builder pollIntervalSeconds(long secs)   { this.pollIntervalSeconds = secs;    return this; }
        public OutboxShardCdcSource build()             { return new OutboxShardCdcSource(this); }
    }
}

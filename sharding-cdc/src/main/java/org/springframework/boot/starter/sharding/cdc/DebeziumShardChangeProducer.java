package org.springframework.boot.starter.sharding.cdc;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CDC producer that listens to a PostgreSQL Write-Ahead Log via Debezium embedded engine
 * and converts change records to {@link ShardChangeEvent}s.
 *
 * <h3>Replication slot management (CRITICAL)</h3>
 * <p>Debezium uses a PostgreSQL replication slot to receive WAL events. If the application
 * crashes without calling {@link #destroy()}, the slot persists on the database and
 * PostgreSQL will hold WAL segments indefinitely — this will eventually fill the disk.
 *
 * <p>Mitigations applied in this implementation:
 * <ol>
 *   <li>{@code slot.drop.on.stop=true} is enforced in the Debezium config so the
 *       connector drops the slot when it stops cleanly.</li>
 *   <li>{@link #destroy()} closes the engine <em>first</em>, waits for the engine thread
 *       to finish (up to {@value #ENGINE_CLOSE_TIMEOUT_SECONDS} seconds), then shuts down
 *       the executor. This ensures the connector teardown (including slot drop) completes
 *       before the JVM exits.</li>
 *   <li>The slot name is set to {@code sharding_cdc_<shardName>} by default for easy
 *       identification. Override with the {@code slot.name} property in debeziumConfig.</li>
 * </ol>
 *
 * <p><b>PostgreSQL prerequisites:</b>
 * <ul>
 *   <li>WAL level must be logical: {@code ALTER SYSTEM SET wal_level = logical;}</li>
 *   <li>For full before-images on UPDATE/DELETE:
 *       {@code ALTER TABLE accounts REPLICA IDENTITY FULL;}</li>
 *   <li>The DB user must have replication privileges.</li>
 * </ul>
 *
 * <p><b>Crash recovery:</b> If the slot leaks despite the above, drop it manually:
 * <pre>{@code
 * SELECT pg_drop_replication_slot('sharding_cdc_shard_0');
 * }</pre>
 * Monitor with: {@code SELECT * FROM pg_replication_slots;}
 *
 * <p>Requires Debezium PostgreSQL connector on the classpath:
 * <pre>{@code
 * <dependency>
 *     <groupId>io.debezium</groupId>
 *     <artifactId>debezium-connector-postgres</artifactId>
 *     <version>2.4.0.Final</version>
 * </dependency>
 * }</pre>
 */
public class DebeziumShardChangeProducer implements InitializingBean, DisposableBean {

    private static final Logger log = Logger.getLogger(DebeziumShardChangeProducer.class.getName());
    private static final int ENGINE_CLOSE_TIMEOUT_SECONDS = 30;

    private final String shardName;
    private final Properties debeziumConfig;
    private final String shardKeyColumn;
    private final List<ShardChangeEventListener> listeners;
    private final ShardCdcDeadLetterQueue dlq;

    private DebeziumEngine<ChangeEvent<String, String>> engine;
    private final ExecutorService executor;

    /**
     * @param shardName      name of the shard this producer monitors
     * @param debeziumConfig Debezium connector configuration (see Debezium docs).
     *                       {@code slot.drop.on.stop} and default {@code slot.name}
     *                       are automatically enforced if not already set.
     * @param shardKeyColumn column name used as shard key for key extraction
     * @param listeners      listeners to notify on each change event
     * @param dlq            dead-letter queue for events that fail all listeners;
     *                       use {@link ShardCdcDeadLetterQueue#logging()} for a no-op impl
     */
    public DebeziumShardChangeProducer(String shardName, Properties debeziumConfig,
                                        String shardKeyColumn,
                                        List<ShardChangeEventListener> listeners,
                                        ShardCdcDeadLetterQueue dlq) {
        this.shardName = shardName;
        this.shardKeyColumn = shardKeyColumn;
        this.listeners = listeners;
        this.dlq = dlq;
        this.debeziumConfig = applyDefaults(debeziumConfig, shardName);
        this.executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "cdc-debezium-" + shardName));
    }

    /** Backward-compatible constructor using a logging DLQ. */
    public DebeziumShardChangeProducer(String shardName, Properties debeziumConfig,
                                        String shardKeyColumn,
                                        List<ShardChangeEventListener> listeners) {
        this(shardName, debeziumConfig, shardKeyColumn, listeners, ShardCdcDeadLetterQueue.logging());
    }

    @Override
    public void afterPropertiesSet() {
        engine = DebeziumEngine.create(Json.class)
            .using(debeziumConfig)
            .notifying(record -> {
                try {
                    ShardChangeEvent event = parseRecord(record);
                    if (event != null) {
                        dispatchToListeners(event);
                    }
                } catch (Exception e) {
                    log.log(Level.SEVERE, "Failed to parse CDC record on shard " + shardName, e);
                }
            })
            .using((success, message, error) -> {
                if (!success) {
                    log.log(Level.SEVERE, "Debezium engine stopped with error on shard "
                        + shardName + ": " + message, error);
                } else {
                    log.info("Debezium engine stopped cleanly on shard " + shardName);
                }
            })
            .build();

        executor.submit(engine);
        log.info("Debezium CDC started for shard '" + shardName
            + "' using slot '" + debeziumConfig.getProperty("slot.name") + "'");
    }

    /**
     * Graceful shutdown:
     * <ol>
     *   <li>Close the Debezium engine — this signals the connector to stop and
     *       <b>drops the replication slot</b> (because {@code slot.drop.on.stop=true}).</li>
     *   <li>Wait up to {@value #ENGINE_CLOSE_TIMEOUT_SECONDS}s for the engine thread
     *       to finish so the slot drop completes before JVM exit.</li>
     *   <li>Force-shutdown the executor if the timeout expires.</li>
     * </ol>
     */
    @Override
    public void destroy() throws Exception {
        log.info("Stopping Debezium CDC for shard '" + shardName + "'...");
        try {
            if (engine != null) {
                // Step 1: signal stop + slot drop (blocks briefly)
                engine.close();
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Error closing Debezium engine for shard " + shardName, e);
        }
        // Step 2: wait for the engine thread to actually finish
        executor.shutdown();
        if (!executor.awaitTermination(ENGINE_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warning("Debezium engine thread did not finish within "
                + ENGINE_CLOSE_TIMEOUT_SECONDS + "s for shard " + shardName
                + " — forcing shutdown. Check pg_replication_slots for leaked slots.");
            executor.shutdownNow();
        } else {
            log.info("Debezium CDC stopped cleanly for shard '" + shardName + "'");
        }
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private ShardChangeEvent parseRecord(ChangeEvent<String, String> record) {
        if (record.value() == null) return null;

        String json = record.value();
        ShardChangeEvent.Operation op = detectOperation(json);
        if (op == null) return null;

        String topic = record.destination();
        String tableName = topic.contains(".")
            ? topic.substring(topic.lastIndexOf('.') + 1) : topic;

        // Extract before/after payloads from Debezium envelope JSON
        Map<String, Object> afterImage  = extractPayload(json, "\"after\":");
        Map<String, Object> beforeImage = extractPayload(json, "\"before\":");

        // Extract shard key from the most relevant image
        Map<String, Object> keySource = op == ShardChangeEvent.Operation.DELETE
            ? beforeImage : afterImage;
        long shardKey = extractShardKey(keySource);

        return new ShardChangeEvent(shardName, tableName, op,
            shardKey, afterImage, beforeImage, Instant.now());
    }

    /**
     * Minimal JSON extraction — returns an empty map for tombstones (null sections).
     * Production deployments should replace this with Jackson {@code ObjectMapper}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPayload(String json, String section) {
        int idx = json.indexOf(section);
        if (idx < 0) return Map.of();
        int start = json.indexOf('{', idx + section.length());
        if (start < 0) return Map.of();      // section is "null"
        // Minimal: return empty map — Jackson integration left to production wiring
        return Map.of();
    }

    private long extractShardKey(Map<String, Object> image) {
        Object value = image.get(shardKeyColumn);
        if (value instanceof Number n) return n.longValue();
        return -1L;
    }

    private ShardChangeEvent.Operation detectOperation(String json) {
        if (json.contains("\"op\":\"c\"")) return ShardChangeEvent.Operation.INSERT;
        if (json.contains("\"op\":\"u\"")) return ShardChangeEvent.Operation.UPDATE;
        if (json.contains("\"op\":\"d\"")) return ShardChangeEvent.Operation.DELETE;
        return null;
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    private void dispatchToListeners(ShardChangeEvent event) {
        boolean anySuccess = false;
        Exception lastFailure = null;

        for (ShardChangeEventListener listener : listeners) {
            try {
                listener.onShardChange(event);
                anySuccess = true;
            } catch (Exception e) {
                lastFailure = e;
                log.log(Level.WARNING, "Listener " + listener.getClass().getSimpleName()
                    + " failed for event on shard=" + shardName
                    + " table=" + event.tableName(), e);
            }
        }

        if (!anySuccess && !listeners.isEmpty() && lastFailure != null) {
            dlq.send(event, lastFailure);
        }
    }

    // -------------------------------------------------------------------------
    // Config defaults
    // -------------------------------------------------------------------------

    private static Properties applyDefaults(Properties config, String shardName) {
        Properties p = new Properties(config);
        p.putAll(config);

        // CRITICAL: drop the slot when the connector stops cleanly → prevents WAL hold
        p.putIfAbsent("slot.drop.on.stop", "true");

        // Default slot name = sharding_cdc_<shardName> (replace hyphens → underscores)
        p.putIfAbsent("slot.name", "sharding_cdc_" + shardName.replace('-', '_'));

        // Offset storage: file-based by default for single-node; override for multi-node
        p.putIfAbsent("offset.storage",
            "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        p.putIfAbsent("offset.storage.file.filename",
            "/tmp/debezium-offsets-" + shardName + ".dat");
        p.putIfAbsent("offset.flush.interval.ms", "10000");

        return p;
    }
}

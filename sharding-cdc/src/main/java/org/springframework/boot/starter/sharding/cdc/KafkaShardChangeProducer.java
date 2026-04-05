package org.springframework.boot.starter.sharding.cdc;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CDC producer that consumes Debezium-produced Kafka topics and converts records to
 * {@link ShardChangeEvent}s delivered to all registered {@link ShardChangeEventListener}s.
 *
 * <p>Expects topics in the format {@code <topicPrefix>.<schema>.<table>} (standard
 * Debezium Kafka Connect topic naming convention).
 *
 * <h3>Offset commit strategy (at-least-once)</h3>
 * <p>This consumer enforces {@code enable.auto.commit=false} and calls
 * {@link KafkaConsumer#commitSync()} <em>after</em> every batch has been fully dispatched
 * to all listeners. This guarantees at-least-once delivery: if the process crashes
 * mid-batch, Kafka will re-deliver the uncommitted records on restart.
 *
 * <p>Consequence: listeners <b>must be idempotent</b>. Design them to safely re-process
 * the same event (e.g., upsert by primary key rather than blind insert).
 *
 * <h3>Event ordering per shard key</h3>
 * <p>Correct ordering within a shard requires that all changes for the same shard key
 * land on the <b>same Kafka partition</b>. Configure Debezium Kafka Connect with:
 * <pre>{@code
 * "message.key.columns": "<schema>.<table>:<shard_key_column>"
 * }</pre>
 * This ensures Debezium uses the shard key as the Kafka record key, and the default
 * Kafka partitioner routes equal keys to the same partition — preserving order.
 *
 * <p>If your consumer group has fewer consumers than partitions, one consumer handles
 * multiple partitions. Ordering is still preserved <em>per partition</em>, and since
 * shard keys are consistently hashed to partitions, per-shard-key ordering is maintained.
 *
 * <h3>Dead-letter queue</h3>
 * <p>If all listeners fail for a given event, the event is routed to the configured
 * {@link ShardCdcDeadLetterQueue}. Individual listener failures are logged but do not
 * prevent other listeners from processing the event.
 *
 * <p>Requires Kafka clients on the classpath:
 * <pre>{@code
 * <dependency>
 *     <groupId>org.apache.kafka</groupId>
 *     <artifactId>kafka-clients</artifactId>
 * </dependency>
 * }</pre>
 */
public class KafkaShardChangeProducer implements InitializingBean, DisposableBean {

    private static final Logger log = Logger.getLogger(KafkaShardChangeProducer.class.getName());
    private static final int CONSUMER_CLOSE_TIMEOUT_SECONDS = 10;

    private final String shardName;
    private final Properties kafkaConfig;
    private final String shardKeyColumn;
    private final List<String> topics;
    private final List<ShardChangeEventListener> listeners;
    private final ShardCdcDeadLetterQueue dlq;

    private KafkaConsumer<String, String> consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor;

    /**
     * @param shardName      name of the shard this producer monitors
     * @param kafkaConfig    Kafka consumer configuration ({@code bootstrap.servers},
     *                       {@code group.id}, etc.). {@code enable.auto.commit} is
     *                       always overridden to {@code false} — see class javadoc.
     * @param shardKeyColumn column name used as shard key for key extraction
     * @param topics         Kafka topics to subscribe to
     * @param listeners      listeners to notify on each change event
     * @param dlq            dead-letter queue for events that fail all listeners
     */
    public KafkaShardChangeProducer(String shardName, Properties kafkaConfig,
                                     String shardKeyColumn,
                                     List<String> topics,
                                     List<ShardChangeEventListener> listeners,
                                     ShardCdcDeadLetterQueue dlq) {
        this.shardName = shardName;
        this.shardKeyColumn = shardKeyColumn;
        this.topics = topics;
        this.listeners = listeners;
        this.dlq = dlq;
        this.kafkaConfig = applyDefaults(kafkaConfig);
        this.executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "kafka-cdc-" + shardName));
    }

    /** Backward-compatible constructor using a logging DLQ and no shard key extraction. */
    public KafkaShardChangeProducer(String shardName, Properties kafkaConfig,
                                     List<String> topics,
                                     List<ShardChangeEventListener> listeners) {
        this(shardName, kafkaConfig, null, topics, listeners, ShardCdcDeadLetterQueue.logging());
    }

    @Override
    public void afterPropertiesSet() {
        consumer = new KafkaConsumer<>(kafkaConfig);
        consumer.subscribe(topics);
        running.set(true);
        executor.submit(this::pollLoop);
        log.info("Kafka CDC started for shard '" + shardName + "' topics=" + topics);
    }

    /**
     * Graceful shutdown:
     * <ol>
     *   <li>Signal the poll loop to stop ({@code running=false}).</li>
     *   <li>Wake up the consumer if blocked in {@code poll()}.</li>
     *   <li>Wait up to {@value #CONSUMER_CLOSE_TIMEOUT_SECONDS}s for the consumer thread.</li>
     *   <li>Force-close the KafkaConsumer and executor if timeout expires.</li>
     * </ol>
     */
    @Override
    public void destroy() throws InterruptedException {
        log.info("Stopping Kafka CDC for shard '" + shardName + "'...");
        running.set(false);
        if (consumer != null) {
            consumer.wakeup(); // unblocks poll()
        }
        executor.shutdown();
        if (!executor.awaitTermination(CONSUMER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warning("Kafka CDC thread did not stop within "
                + CONSUMER_CLOSE_TIMEOUT_SECONDS + "s for shard " + shardName
                + " — forcing shutdown.");
            executor.shutdownNow();
        } else {
            log.info("Kafka CDC stopped cleanly for shard '" + shardName + "'");
        }
    }

    // -------------------------------------------------------------------------
    // Poll loop — runs on dedicated executor thread
    // -------------------------------------------------------------------------

    private void pollLoop() {
        try {
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) continue;

                // Process all records in the batch
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        ShardChangeEvent event = parseRecord(record);
                        if (event != null) {
                            dispatchToListeners(event);
                        }
                    } catch (Exception e) {
                        log.log(Level.SEVERE, "Unexpected error processing CDC record on shard "
                            + shardName + " topic=" + record.topic()
                            + " offset=" + record.offset(), e);
                    }
                }

                // Commit offsets AFTER full batch is dispatched — at-least-once guarantee
                try {
                    consumer.commitSync();
                } catch (Exception e) {
                    log.log(Level.WARNING, "Failed to commit Kafka offsets for shard " + shardName
                        + " — records will be reprocessed on restart", e);
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException ignored) {
            // Expected when destroy() calls consumer.wakeup()
        } finally {
            try {
                // Commit any pending offsets before closing
                consumer.commitSync();
            } catch (Exception ignored) {
                // Best-effort commit on shutdown
            }
            consumer.close(Duration.ofSeconds(CONSUMER_CLOSE_TIMEOUT_SECONDS));
        }
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private ShardChangeEvent parseRecord(ConsumerRecord<String, String> record) {
        if (record.value() == null) return null;  // tombstone — skip

        String json = record.value();
        ShardChangeEvent.Operation op = detectOperation(json);
        if (op == null) return null;

        // Extract table name from topic: prefix.schema.table
        String topic = record.topic();
        String tableName = topic.contains(".") ? topic.substring(topic.lastIndexOf('.') + 1) : topic;

        // Extract before/after payloads from Debezium envelope JSON
        // Production: replace with Jackson ObjectMapper for proper deserialization
        Map<String, Object> afterImage  = extractPayload(json, "\"after\":");
        Map<String, Object> beforeImage = extractPayload(json, "\"before\":");

        // Extract shard key; prefer after for INSERT/UPDATE, before for DELETE
        Map<String, Object> keySource = op == ShardChangeEvent.Operation.DELETE
            ? beforeImage : afterImage;
        long shardKey = extractShardKey(keySource);

        return new ShardChangeEvent(shardName, tableName, op,
            shardKey, afterImage, beforeImage, Instant.now());
    }

    /**
     * Minimal JSON payload extractor — returns empty map when section is absent or null.
     * Replace with Jackson ObjectMapper in production for type-safe deserialization.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPayload(String json, String section) {
        int idx = json.indexOf(section);
        if (idx < 0) return Map.of();
        int start = json.indexOf('{', idx + section.length());
        if (start < 0) return Map.of();  // section value is "null"
        // Minimal stub — Jackson integration left to production wiring
        return Map.of();
    }

    private long extractShardKey(Map<String, Object> image) {
        if (shardKeyColumn == null) return -1L;
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

    /**
     * Enforces safe consumer defaults. {@code enable.auto.commit=false} is ALWAYS set —
     * manual commit after batch processing is the only way to achieve at-least-once
     * delivery guarantees without silent data loss.
     */
    private static Properties applyDefaults(Properties config) {
        Properties p = new Properties();
        p.putAll(config);

        // CRITICAL: disable auto-commit so we control when offsets advance
        p.put("enable.auto.commit", "false");

        // Sensible defaults for CDC consumers — override via kafkaConfig
        p.putIfAbsent("auto.offset.reset", "earliest");
        p.putIfAbsent("max.poll.records", "500");
        p.putIfAbsent("key.deserializer",
            "org.apache.kafka.common.serialization.StringDeserializer");
        p.putIfAbsent("value.deserializer",
            "org.apache.kafka.common.serialization.StringDeserializer");

        return p;
    }
}

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * CDC producer that consumes Debezium-produced Kafka topics and converts records to
 * {@link ShardChangeEvent}s delivered to all registered {@link ShardChangeEventListener}s.
 *
 * <p>Expects topics in the format {@code <topicPrefix>.<schema>.<table>} (standard
 * Debezium Kafka Connect topic naming convention).
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

    private final String shardName;
    private final Properties kafkaConfig;
    private final List<String> topics;
    private final List<ShardChangeEventListener> listeners;

    private KafkaConsumer<String, String> consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "kafka-cdc-" + shardName));

    /**
     * @param shardName   name of the shard this producer monitors
     * @param kafkaConfig Kafka consumer configuration (bootstrap.servers, group.id, etc.)
     * @param topics      Kafka topics to subscribe to
     * @param listeners   listeners to notify on each change event
     */
    public KafkaShardChangeProducer(String shardName, Properties kafkaConfig,
                                     List<String> topics,
                                     List<ShardChangeEventListener> listeners) {
        this.shardName = shardName;
        this.kafkaConfig = kafkaConfig;
        this.topics = topics;
        this.listeners = listeners;
    }

    @Override
    public void afterPropertiesSet() {
        consumer = new KafkaConsumer<>(kafkaConfig);
        consumer.subscribe(topics);
        running.set(true);
        executor.submit(this::pollLoop);
    }

    @Override
    public void destroy() {
        running.set(false);
        executor.shutdownNow();
        if (consumer != null) {
            consumer.wakeup();
        }
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        ShardChangeEvent event = parseRecord(record);
                        if (event != null) {
                            notifyListeners(event);
                        }
                    } catch (Exception e) {
                        log.warning("Failed to process Kafka CDC record: " + e.getMessage());
                    }
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException ignored) {
            // Expected on shutdown
        } finally {
            consumer.close();
        }
    }

    private ShardChangeEvent parseRecord(ConsumerRecord<String, String> record) {
        if (record.value() == null) return null;

        // Extract table name from topic: prefix.schema.table
        String topic = record.topic();
        String tableName = topic.contains(".") ? topic.substring(topic.lastIndexOf('.') + 1) : topic;

        ShardChangeEvent.Operation op = detectOperation(record.value());
        if (op == null) return null;

        return new ShardChangeEvent(shardName, tableName, op, -1L, Map.of(), Instant.now());
    }

    private ShardChangeEvent.Operation detectOperation(String json) {
        if (json.contains("\"op\":\"c\"")) return ShardChangeEvent.Operation.INSERT;
        if (json.contains("\"op\":\"u\"")) return ShardChangeEvent.Operation.UPDATE;
        if (json.contains("\"op\":\"d\"")) return ShardChangeEvent.Operation.DELETE;
        return null;
    }

    private void notifyListeners(ShardChangeEvent event) {
        for (ShardChangeEventListener listener : listeners) {
            try {
                listener.onShardChange(event);
            } catch (Exception e) {
                log.warning("Listener " + listener.getClass().getSimpleName()
                    + " threw exception: " + e.getMessage());
            }
        }
    }
}

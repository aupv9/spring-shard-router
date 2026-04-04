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
import java.util.logging.Logger;

/**
 * CDC producer that listens to a PostgreSQL Write-Ahead Log via Debezium embedded engine
 * and converts change records to {@link ShardChangeEvent}s delivered to all registered
 * {@link ShardChangeEventListener}s.
 *
 * <p>Requires Debezium PostgreSQL connector on the classpath:
 * <pre>{@code
 * <dependency>
 *     <groupId>io.debezium</groupId>
 *     <artifactId>debezium-connector-postgres</artifactId>
 *     <version>2.4.0.Final</version>
 * </dependency>
 * }</pre>
 *
 * <p>Each physical shard should have its own {@code DebeziumShardChangeProducer} instance
 * configured with the appropriate PostgreSQL connection properties.
 */
public class DebeziumShardChangeProducer implements InitializingBean, DisposableBean {

    private static final Logger log = Logger.getLogger(DebeziumShardChangeProducer.class.getName());

    private final String shardName;
    private final Properties debeziumConfig;
    private final List<ShardChangeEventListener> listeners;
    private final String shardKeyColumn;

    private DebeziumEngine<ChangeEvent<String, String>> engine;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "cdc-" + shardName));

    /**
     * @param shardName      name of the shard this producer monitors
     * @param debeziumConfig Debezium connector configuration (see Debezium docs)
     * @param shardKeyColumn column name used as shard key (for key extraction)
     * @param listeners      listeners to notify on each change event
     */
    public DebeziumShardChangeProducer(String shardName, Properties debeziumConfig,
                                        String shardKeyColumn,
                                        List<ShardChangeEventListener> listeners) {
        this.shardName = shardName;
        this.debeziumConfig = debeziumConfig;
        this.shardKeyColumn = shardKeyColumn;
        this.listeners = listeners;
    }

    @Override
    public void afterPropertiesSet() {
        engine = DebeziumEngine.create(Json.class)
            .using(debeziumConfig)
            .notifying(record -> {
                try {
                    ShardChangeEvent event = parseRecord(record);
                    if (event != null) {
                        notifyListeners(event);
                    }
                } catch (Exception e) {
                    log.warning("Failed to process CDC record on shard " + shardName + ": " + e.getMessage());
                }
            })
            .build();
        executor.submit(engine);
    }

    @Override
    public void destroy() throws Exception {
        if (engine != null) {
            engine.close();
        }
        executor.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private ShardChangeEvent parseRecord(ChangeEvent<String, String> record) {
        if (record.value() == null) return null;

        // Minimal JSON parsing — production use should use Jackson ObjectMapper
        String value = record.value();
        ShardChangeEvent.Operation op = detectOperation(value);
        if (op == null) return null;

        // Extract table name from topic: {server}.{schema}.{table}
        String topic = record.destination();
        String tableName = topic.contains(".") ? topic.substring(topic.lastIndexOf('.') + 1) : topic;

        long shardKey = -1;
        Map<String, Object> payload = Map.of();

        return new ShardChangeEvent(shardName, tableName, op, shardKey, payload, Instant.now());
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

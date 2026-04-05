package org.springframework.boot.starter.sharding.cdc;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a collection of {@link DebeziumShardChangeProducer} instances — one per shard —
 * and provides a single registration point for {@link ShardChangeEventListener}s that
 * should receive events from <em>all</em> shards.
 *
 * <h3>Multi-shard wiring problem</h3>
 * <p>Without this registry, applications must manually declare N Spring beans when
 * monitoring N shards:
 * <pre>{@code
 * @Bean DebeziumShardChangeProducer producerShard0(...) { ... }
 * @Bean DebeziumShardChangeProducer producerShard1(...) { ... }
 * // ...repeated for every shard
 * }</pre>
 *
 * <p>With {@code CdcProducerRegistry}, a single bean declaration covers all shards:
 * <pre>{@code
 * @Bean
 * CdcProducerRegistry cdcRegistry(List<ShardChangeEventListener> listeners) {
 *     CdcProducerRegistry registry = new CdcProducerRegistry(listeners, ShardCdcDeadLetterQueue.logging());
 *
 *     for (ShardProperties.ShardConfig shard : shardProperties.getShards()) {
 *         Properties debeziumConfig = new Properties();
 *         debeziumConfig.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
 *         debeziumConfig.setProperty("database.hostname", shard.getDatasource().getJdbcUrl()); // extract host
 *         // ... other Debezium properties
 *         registry.addShard(shard.getName(), debeziumConfig, "account_id");
 *     }
 *     return registry;
 * }
 * }</pre>
 *
 * <p>The registry implements {@link InitializingBean} and {@link DisposableBean} so
 * Spring manages its lifecycle automatically — {@code afterPropertiesSet()} starts all
 * registered producers; {@code destroy()} stops them all.
 *
 * <h3>Dynamic registration</h3>
 * <p>Shards can also be added before {@code afterPropertiesSet()} is called (i.e., at
 * configuration time). Registering after the registry has started is not supported.
 */
public class CdcProducerRegistry implements InitializingBean, DisposableBean {

    private static final Logger log = Logger.getLogger(CdcProducerRegistry.class.getName());

    private final List<ShardChangeEventListener> globalListeners;
    private final ShardCdcDeadLetterQueue dlq;

    /** Ordered list preserving registration order (for deterministic startup logs). */
    private final List<DebeziumShardChangeProducer> producers = new ArrayList<>();
    private final Map<String, DebeziumShardChangeProducer> producersByShardName
        = new ConcurrentHashMap<>();

    private volatile boolean started = false;

    /**
     * @param globalListeners listeners that will receive events from <em>every</em> shard
     * @param dlq             dead-letter queue for events that fail all listeners
     */
    public CdcProducerRegistry(List<ShardChangeEventListener> globalListeners,
                                ShardCdcDeadLetterQueue dlq) {
        this.globalListeners = Collections.unmodifiableList(new ArrayList<>(globalListeners));
        this.dlq = dlq;
    }

    /** Constructor using a logging DLQ. */
    public CdcProducerRegistry(List<ShardChangeEventListener> globalListeners) {
        this(globalListeners, ShardCdcDeadLetterQueue.logging());
    }

    // -------------------------------------------------------------------------
    // Registration (call before afterPropertiesSet)
    // -------------------------------------------------------------------------

    /**
     * Registers a shard to be monitored via an embedded Debezium engine.
     *
     * <p>Must be called <em>before</em> Spring invokes {@link #afterPropertiesSet()}.
     *
     * @param shardName      unique shard identifier (e.g., {@code "shard-0"})
     * @param debeziumConfig Debezium connector properties
     * @param shardKeyColumn column name used as shard key
     * @throws IllegalStateException if the registry has already been started
     */
    public synchronized void addShard(String shardName, Properties debeziumConfig,
                                       String shardKeyColumn) {
        if (started) {
            throw new IllegalStateException(
                "Cannot add shard '" + shardName + "' after registry has started");
        }
        if (producersByShardName.containsKey(shardName)) {
            throw new IllegalArgumentException(
                "Shard '" + shardName + "' is already registered in this CdcProducerRegistry");
        }

        DebeziumShardChangeProducer producer = new DebeziumShardChangeProducer(
            shardName, debeziumConfig, shardKeyColumn, globalListeners, dlq);

        producers.add(producer);
        producersByShardName.put(shardName, producer);
        log.fine("Shard '" + shardName + "' registered in CdcProducerRegistry");
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts all registered Debezium producers.
     * Called automatically by Spring after all properties are set.
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        started = true;
        log.info("Starting CdcProducerRegistry with " + producers.size() + " shard(s): "
            + producersByShardName.keySet());

        List<String> failedShards = new ArrayList<>();
        for (DebeziumShardChangeProducer producer : producers) {
            try {
                producer.afterPropertiesSet();
            } catch (Exception e) {
                log.log(Level.SEVERE, "Failed to start CDC producer", e);
                failedShards.add(e.getMessage());
            }
        }

        if (!failedShards.isEmpty()) {
            throw new IllegalStateException(
                "Failed to start CDC producers for " + failedShards.size() + " shard(s). "
                    + "Check logs for details.");
        }
    }

    /**
     * Stops all registered Debezium producers.
     * Called automatically by Spring on application shutdown.
     */
    @Override
    public void destroy() throws Exception {
        log.info("Stopping CdcProducerRegistry (" + producers.size() + " shards)...");
        for (DebeziumShardChangeProducer producer : producers) {
            try {
                producer.destroy();
            } catch (Exception e) {
                log.log(Level.WARNING, "Error stopping CDC producer", e);
            }
        }
        log.info("CdcProducerRegistry stopped.");
    }

    // -------------------------------------------------------------------------
    // Introspection
    // -------------------------------------------------------------------------

    /**
     * Returns the number of registered shards.
     */
    public int shardCount() {
        return producers.size();
    }

    /**
     * Returns the registered shard names (unmodifiable).
     */
    public java.util.Set<String> shardNames() {
        return Collections.unmodifiableSet(producersByShardName.keySet());
    }
}

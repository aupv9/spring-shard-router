package org.springframework.boot.starter.sharding.cdc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Lifecycle manager for all registered {@link ShardCdcSource}s.
 *
 * <p>On {@link #start()}, creates a {@link ShardCdcDispatcher} from the registered
 * listeners and starts every source. On {@link #stop()}, stops all sources in order.
 *
 * <p>This bean is registered by {@link ShardingCdcAutoConfiguration} and is started
 * automatically via {@link jakarta.annotation.PostConstruct} / {@link jakarta.annotation.PreDestroy}.
 * Alternatively, call {@link #start()} and {@link #stop()} manually.
 *
 * <p>Example — wire two sources with a custom listener:
 * <pre>{@code
 * @Bean
 * ShardCdcSource transactionPollSource(ShardRouter router) {
 *     return PollingShardCdcSource.builder()
 *         .shardIndex(0)
 *         .dataSource(router.getShard(0).dataSource())
 *         .table("transactions")
 *         .shardKeyColumn("account_id")
 *         .pollIntervalSeconds(3)
 *         .build();
 * }
 *
 * @Bean
 * ShardCdcListener auditListener() {
 *     return event -> log.info("Change: {}", event);
 * }
 * }</pre>
 */
public class ShardCdcManager {

    private static final Logger log = LoggerFactory.getLogger(ShardCdcManager.class);

    private final List<ShardCdcSource>   sources;
    private final List<ShardCdcListener> listeners;

    private ShardCdcDispatcher dispatcher;

    public ShardCdcManager(List<ShardCdcSource> sources, List<ShardCdcListener> listeners) {
        this.sources   = List.copyOf(sources);
        this.listeners = List.copyOf(listeners);
    }

    /**
     * Create dispatcher and start all registered sources.
     * Called automatically by {@link ShardingCdcAutoConfiguration} via {@code @PostConstruct}.
     */
    public void start() {
        if (sources.isEmpty()) {
            log.info("[cdc] no sources registered — CDC manager started but idle");
            return;
        }
        log.info("[cdc] starting {} source(s) with {} listener(s)", sources.size(), listeners.size());
        this.dispatcher = new ShardCdcDispatcher(listeners);
        for (ShardCdcSource source : sources) {
            try {
                source.start(dispatcher);
                log.info("[cdc] started source: {}", source.name());
            } catch (Exception ex) {
                log.error("[cdc] failed to start source {}: {}", source.name(), ex.getMessage(), ex);
            }
        }
    }

    /**
     * Stop all registered sources.
     * Called automatically by {@link ShardingCdcAutoConfiguration} via {@code @PreDestroy}.
     */
    public void stop() {
        log.info("[cdc] stopping {} source(s)", sources.size());
        for (ShardCdcSource source : sources) {
            try {
                source.stop();
                log.info("[cdc] stopped source: {}", source.name());
            } catch (Exception ex) {
                log.error("[cdc] error stopping source {}: {}", source.name(), ex.getMessage(), ex);
            }
        }
    }

    public int sourceCount()   { return sources.size(); }
    public int listenerCount() { return listeners.size(); }
}

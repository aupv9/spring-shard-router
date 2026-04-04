package org.springframework.boot.starter.sharding.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.starter.sharding.core.ConsistentHashShardRouter;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardRouter;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing shards at runtime — adding, removing, listing, and overriding.
 *
 * <p>Only {@link ConsistentHashShardRouter} supports dynamic shard mutations; using this
 * service with a {@link org.springframework.boot.starter.sharding.core.HashShardRouter}
 * will throw {@link UnsupportedOperationException} for add/remove operations.
 *
 * <p>Enable via {@code sharding.management.enabled=true}.
 */
public class ShardManagementService implements DisposableBean {

    private final ShardRouter shardRouter;
    private final ShardingAutoConfiguration autoConfiguration;
    /** Tracks dynamically-added DataSources for clean shutdown. */
    private final Map<String, DataSource> dynamicDataSources = new ConcurrentHashMap<>();

    public ShardManagementService(ShardRouter shardRouter,
                                  ShardingAutoConfiguration autoConfiguration) {
        this.shardRouter = shardRouter;
        this.autoConfiguration = autoConfiguration;
    }

    /**
     * Add a new shard at runtime using the provided configuration.
     * Creates a HikariCP pool and registers the shard in the router.
     *
     * @param shardConfig configuration for the new shard
     * @return the newly created {@link Shard}
     */
    public Shard addShard(ShardProperties.ShardConfig shardConfig) {
        int newIndex = shardRouter.getShardCount();
        DataSource dataSource = autoConfiguration.createDataSource(
            shardConfig.getDatasource(), "shard-" + shardConfig.getName());
        dynamicDataSources.put(shardConfig.getName(), dataSource);

        Shard shard = Shard.of(shardConfig.getName(), newIndex, dataSource);
        shardRouter.addShard(shard);
        return shard;
    }

    /**
     * Remove a shard by name from the router. The underlying DataSource is closed.
     *
     * @param shardName name of the shard to remove
     */
    public void removeShard(String shardName) {
        List<Shard> shards = listShards();
        int index = -1;
        for (Shard s : shards) {
            if (s.name().equals(shardName)) {
                index = s.index();
                break;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("No shard found with name: " + shardName);
        }
        shardRouter.removeShard(index);

        DataSource ds = dynamicDataSources.remove(shardName);
        closeQuietly(ds);
    }

    /**
     * List all currently registered shards.
     */
    public List<Shard> listShards() {
        List<Shard> result = new ArrayList<>();
        for (int i = 0; i < shardRouter.getShardCount(); i++) {
            result.add(shardRouter.getShard(i));
        }
        return result;
    }

    /**
     * Add a shard key override — routes the given key to a specific shard index
     * regardless of hash.
     *
     * @param shardKey    the key to pin
     * @param shardIndex  target shard index
     */
    public void addOverride(long shardKey, int shardIndex) {
        if (shardRouter instanceof ConsistentHashShardRouter chr) {
            chr.addOverride(shardKey, shardIndex);
        } else {
            throw new UnsupportedOperationException(
                "Override management requires ConsistentHashShardRouter");
        }
    }

    /**
     * Remove a shard key override.
     *
     * @param shardKey the key whose override should be removed
     */
    public void removeOverride(long shardKey) {
        if (shardRouter instanceof ConsistentHashShardRouter chr) {
            chr.removeOverride(shardKey);
        } else {
            throw new UnsupportedOperationException(
                "Override management requires ConsistentHashShardRouter");
        }
    }

    @Override
    public void destroy() {
        dynamicDataSources.values().forEach(this::closeQuietly);
        dynamicDataSources.clear();
    }

    private void closeQuietly(DataSource ds) {
        if (ds instanceof HikariDataSource hds) {
            try {
                hds.close();
            } catch (Exception ignored) {
            }
        }
    }
}

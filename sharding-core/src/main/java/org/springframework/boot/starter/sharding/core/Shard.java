package org.springframework.boot.starter.sharding.core;

import javax.sql.DataSource;
import java.util.List;

/**
 * Represents a single shard with its metadata and data source(s).
 *
 * <p>For read/write splitting, a shard may carry one or more read replica
 * {@code DataSource}s in addition to the primary. When no replicas are configured
 * the list is empty and all traffic goes to the primary.
 */
public record Shard(
    String name,
    int index,
    DataSource dataSource,
    List<DataSource> readReplicaDataSources
) {

    /**
     * Create a shard with no read replicas (primary-only mode).
     */
    public static Shard of(String name, int index, DataSource dataSource) {
        return new Shard(name, index, dataSource, List.of());
    }

    /**
     * Create a shard with one or more read replicas.
     *
     * @param name       shard name
     * @param index      zero-based shard index
     * @param dataSource primary (write) data source
     * @param replicas   read replica data sources; must not be null
     */
    public static Shard withReplicas(String name, int index, DataSource dataSource,
                                     List<DataSource> replicas) {
        return new Shard(name, index, dataSource, List.copyOf(replicas));
    }

    /**
     * Returns {@code true} if this shard has at least one configured read replica.
     */
    public boolean hasReadReplicas() {
        return !readReplicaDataSources.isEmpty();
    }
}

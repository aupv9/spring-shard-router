package org.springframework.boot.starter.sharding.core;

import javax.sql.DataSource;

/**
 * Represents a single shard with its metadata and data source
 */
public record Shard(
    String name,
    int index,
    DataSource dataSource
) {
    
    public static Shard of(String name, int index, DataSource dataSource) {
        return new Shard(name, index, dataSource);
    }
}
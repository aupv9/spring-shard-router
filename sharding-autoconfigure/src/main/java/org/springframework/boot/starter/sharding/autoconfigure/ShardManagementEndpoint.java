package org.springframework.boot.starter.sharding.autoconfigure;

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.starter.sharding.core.Shard;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring Boot Actuator endpoint for shard management.
 *
 * <p>Exposed at {@code /actuator/shards} when {@code sharding.management.enabled=true}
 * and {@code spring-boot-starter-actuator} is on the classpath.
 *
 * <p><b>Security note:</b> this endpoint exposes write operations. Restrict access
 * via {@code management.endpoints.web.exposure.include} and Spring Security.
 */
@Endpoint(id = "shards")
public class ShardManagementEndpoint {

    private final ShardManagementService managementService;

    public ShardManagementEndpoint(ShardManagementService managementService) {
        this.managementService = managementService;
    }

    /**
     * List all configured shards.
     * Maps to {@code GET /actuator/shards}.
     */
    @ReadOperation
    public List<Map<String, Object>> listShards() {
        return managementService.listShards().stream()
            .map(this::toMap)
            .collect(Collectors.toList());
    }

    /**
     * Add a new shard at runtime.
     * Maps to {@code POST /actuator/shards}.
     *
     * @param name     shard name
     * @param jdbcUrl  JDBC URL
     * @param username DB username
     * @param password DB password
     */
    @WriteOperation
    public Map<String, Object> addShard(String name, String jdbcUrl,
                                        String username, String password) {
        ShardProperties.ShardConfig config = new ShardProperties.ShardConfig();
        config.setName(name);
        ShardProperties.DataSourceConfig dsConfig = new ShardProperties.DataSourceConfig();
        dsConfig.setJdbcUrl(jdbcUrl);
        dsConfig.setUsername(username);
        dsConfig.setPassword(password);
        config.setDatasource(dsConfig);

        Shard shard = managementService.addShard(config);
        return toMap(shard);
    }

    /**
     * Remove a shard by name.
     * Maps to {@code DELETE /actuator/shards}.
     *
     * @param name shard name to remove
     */
    @DeleteOperation
    public void removeShard(String name) {
        managementService.removeShard(name);
    }

    private Map<String, Object> toMap(Shard shard) {
        return Map.of(
            "name", shard.name(),
            "index", shard.index(),
            "hasReadReplicas", shard.hasReadReplicas(),
            "replicaCount", shard.readReplicaDataSources().size()
        );
    }
}

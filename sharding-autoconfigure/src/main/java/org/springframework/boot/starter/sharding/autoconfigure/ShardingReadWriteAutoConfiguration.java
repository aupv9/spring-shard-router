package org.springframework.boot.starter.sharding.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.readwrite.ReadWriteRoutingDataSource;
import org.springframework.boot.starter.sharding.readwrite.ReplicaAwareShardContextTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;

/**
 * Auto-configuration for read/write splitting.
 *
 * <p>Activates when {@code sharding.read-write-splitting.enabled=true} and the
 * {@code sharding-readwrite} module is on the classpath.
 *
 * <p>Replaces the standard {@code shardingDataSource} bean with a
 * {@link ReadWriteRoutingDataSource} that routes {@code SELECT} operations to
 * read replicas while writes continue to the primary.
 */
@AutoConfiguration(after = ShardingAutoConfiguration.class)
@ConditionalOnProperty(name = "sharding.read-write-splitting.enabled", havingValue = "true")
@ConditionalOnClass(ReadWriteRoutingDataSource.class)
public class ShardingReadWriteAutoConfiguration {

    /**
     * Override the routing DataSource with a read/write-aware version.
     * Declared with name {@code shardingDataSource} to replace the one in
     * {@link ShardingAutoConfiguration}.
     */
    @Bean(name = "shardingDataSource")
    @ConditionalOnMissingBean(name = "shardingDataSource")
    public DataSource shardingDataSource(ShardRouter shardRouter) {
        return new LazyConnectionDataSourceProxy(new ReadWriteRoutingDataSource(shardRouter));
    }

    /**
     * Replace the task decorator with a replica-aware version that also propagates
     * the read-only flag to async threads.
     */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    public ReplicaAwareShardContextTaskDecorator replicaAwareShardContextTaskDecorator() {
        return new ReplicaAwareShardContextTaskDecorator();
    }
}

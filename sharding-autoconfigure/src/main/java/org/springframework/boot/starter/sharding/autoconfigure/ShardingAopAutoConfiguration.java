package org.springframework.boot.starter.sharding.autoconfigure;

import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.starter.sharding.aop.ShardByAspect;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the {@code @ShardBy} AOP aspect.
 *
 * <p>Activates only when:
 * <ul>
 *   <li>{@code sharding.enabled=true}</li>
 *   <li>Spring AOP ({@code aspectjweaver}) is on the classpath</li>
 *   <li>{@link ShardByAspect} is on the classpath (i.e., {@code sharding-aop} module is present)</li>
 * </ul>
 */
@AutoConfiguration(after = ShardingAutoConfiguration.class)
@ConditionalOnProperty(name = "sharding.enabled", havingValue = "true")
@ConditionalOnClass({Aspect.class, ShardByAspect.class})
public class ShardingAopAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ShardByAspect shardByAspect() {
        return new ShardByAspect();
    }
}

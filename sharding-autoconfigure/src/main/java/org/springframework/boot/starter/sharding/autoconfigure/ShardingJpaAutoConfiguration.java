package org.springframework.boot.starter.sharding.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.starter.sharding.jpa.ShardEntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.support.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for JPA sharding support
 * Activated when JPA is on classpath and sharding is enabled
 */
@AutoConfiguration
@ConditionalOnClass({EntityManager.class, LocalContainerEntityManagerFactoryBean.class})
@ConditionalOnProperty(name = "sharding.enabled", havingValue = "true")
@EnableConfigurationProperties({ShardProperties.class, JpaProperties.class})
public class ShardingJpaAutoConfiguration {
    
    /**
     * Create routing EntityManagerFactory for JPA operations
     */
    @Bean
    @ConditionalOnMissingBean(name = "shardingEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean shardingEntityManagerFactory(
            DataSource shardingDataSource,
            ShardProperties shardProperties,
            JpaProperties jpaProperties) {

        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(shardingDataSource);

        List<String> packages = shardProperties.getEntityPackages();
        if (packages.isEmpty()) {
            throw new IllegalStateException(
                "sharding.entity-packages must be configured. " +
                "Example: sharding.entity-packages=com.example.domain"
            );
        }
        factory.setPackagesToScan(packages.toArray(String[]::new));
        
        // Use Hibernate as JPA provider
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(jpaProperties.isGenerateDdl());
        vendorAdapter.setShowSql(jpaProperties.isShowSql());
        factory.setJpaVendorAdapter(vendorAdapter);
        
        // Set JPA properties (spring.jpa.properties.* values)
        Map<String, Object> jpaPropertiesMap = new HashMap<>(jpaProperties.getProperties());

        // Respect spring.jpa.hibernate.ddl-auto; default to "none" because each shard
        // database must be managed independently — Hibernate can only see one shard at a
        // time via the RoutingDataSource, so it cannot create or validate all shards.
        if (!jpaPropertiesMap.containsKey("hibernate.hbm2ddl.auto")) {
            String ddlAuto = jpaProperties.getHibernate().getDdlAuto();
            jpaPropertiesMap.put("hibernate.hbm2ddl.auto", ddlAuto != null ? ddlAuto : "none");
        }

        // PgBouncer-safe: do not autocommit connections returned from the pool
        jpaPropertiesMap.put("hibernate.connection.provider_disables_autocommit", "true");
        
        factory.setJpaPropertyMap(jpaPropertiesMap);
        factory.setPersistenceUnitName("sharding-persistence-unit");
        
        return factory;
    }
    
    /**
     * Create JPA transaction manager for sharding
     */
    @Bean
    @ConditionalOnMissingBean(name = "shardJpaTransactionManager")
    public PlatformTransactionManager shardJpaTransactionManager(EntityManagerFactory shardingEntityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(shardingEntityManagerFactory);
        return transactionManager;
    }

    /**
     * Shard-aware EntityManager wrapper for advanced JPA operations.
     * Uses a shared (thread-safe) EntityManager proxy backed by the sharding EMF,
     * the same pattern used internally by Spring Data JPA repositories.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardEntityManager shardEntityManager(EntityManagerFactory shardingEntityManagerFactory) {
        EntityManager sharedEm = SharedEntityManagerCreator.createSharedEntityManager(shardingEntityManagerFactory);
        return new ShardEntityManager(sharedEm);
    }
}
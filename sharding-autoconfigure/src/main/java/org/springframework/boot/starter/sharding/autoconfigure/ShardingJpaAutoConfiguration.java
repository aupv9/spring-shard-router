package org.springframework.boot.starter.sharding.autoconfigure;

import org.springframework.boot.starter.sharding.core.ShardRouter;
import org.springframework.boot.starter.sharding.jpa.ShardEntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.HashMap;
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
            JpaProperties jpaProperties) {
        
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(shardingDataSource);
        factory.setPackagesToScan("com.fintech"); // Scan for entities
        
        // Use Hibernate as JPA provider
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(jpaProperties.isGenerateDdl());
        vendorAdapter.setShowSql(jpaProperties.isShowSql());
        factory.setJpaVendorAdapter(vendorAdapter);
        
        // Set JPA properties
        Map<String, Object> jpaPropertiesMap = new HashMap<>(jpaProperties.getProperties());
        
        // Add Hibernate-specific properties for sharding
        jpaPropertiesMap.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        jpaPropertiesMap.put("hibernate.hbm2ddl.auto", "validate");
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
}
package org.springframework.boot.starter.sharding.jpa;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify shard key field for automatic routing
 * Can be used on repository methods or service methods
 */
@Target({ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ShardBy {
    
    /**
     * The parameter name or field name to use as shard key
     * If empty, will use the first parameter as shard key
     */
    String value() default "";
    
    /**
     * Whether to extract shard key from entity field
     * If true, will look for the field specified in value() within the entity
     */
    boolean fromEntity() default false;
}
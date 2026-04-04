package org.springframework.boot.starter.sharding.aop;

/**
 * Exception thrown when a shard key cannot be extracted from a method invocation.
 * Typically caused by a missing or misnamed parameter when using {@code @ShardBy}.
 */
public class ShardKeyExtractionException extends RuntimeException {

    public ShardKeyExtractionException(String message) {
        super(message);
    }

    public ShardKeyExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}

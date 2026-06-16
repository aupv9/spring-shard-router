package org.springframework.boot.starter.sharding.core;

import org.springframework.core.task.TaskDecorator;

/**
 * Spring {@link TaskDecorator} that propagates the current thread's shard context
 * into tasks executed by a {@code ThreadPoolTaskExecutor} (i.e. {@code @Async} methods).
 *
 * <p>Captures both the shard key <em>and</em> the read-only flag from the calling thread
 * so that async tasks route to the correct shard and correctly use read replicas when
 * the caller holds a {@code @Transactional(readOnly=true)} context.
 *
 * <p>Register this bean and Spring's async executor will pick it up automatically:
 * <pre>{@code
 * @Configuration
 * @EnableAsync
 * public class AsyncConfig implements AsyncConfigurer {
 *     @Override
 *     public Executor getAsyncExecutor() {
 *         ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 *         executor.setTaskDecorator(new ShardContextTaskDecorator());
 *         executor.initialize();
 *         return executor;
 *     }
 * }
 * }</pre>
 *
 * <p>Or simply declare it as a {@code @Bean} — {@link ShardingAutoConfiguration} does
 * this automatically when sharding is enabled.
 *
 * <p>When read/write splitting is also active, {@link
 * org.springframework.boot.starter.sharding.readwrite.ReplicaAwareShardContextTaskDecorator}
 * is registered instead — it provides identical behaviour via a separate bean, keeping
 * the {@code sharding-core} module free of a compile dependency on {@code sharding-readwrite}.
 */
public class ShardContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture both the shard key and the read-only flag from the calling thread.
        // The read-only flag must be copied so that async tasks running in a thread-pool
        // thread also route to read replicas when the caller requested read-only routing.
        Long    shardKey = ShardContext.get();
        boolean readOnly = ShardContext.isReadOnly();
        return () -> {
            try {
                if (shardKey != null) {
                    ShardContext.set(shardKey);
                }
                ShardContext.setReadOnly(readOnly);
                runnable.run();
            } finally {
                ShardContext.clear();
            }
        };
    }
}

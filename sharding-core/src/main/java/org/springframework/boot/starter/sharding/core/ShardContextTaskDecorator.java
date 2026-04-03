package org.springframework.boot.starter.sharding.core;

import org.springframework.core.task.TaskDecorator;

/**
 * Spring {@link TaskDecorator} that propagates the current thread's shard key
 * into tasks executed by a {@code ThreadPoolTaskExecutor} (i.e. {@code @Async} methods).
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
 */
public class ShardContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture shard key from the calling (HTTP request) thread
        Long shardKey = ShardContext.get();
        return () -> {
            try {
                if (shardKey != null) {
                    ShardContext.set(shardKey);
                }
                runnable.run();
            } finally {
                ShardContext.clear();
            }
        };
    }
}

package org.springframework.boot.starter.sharding.readwrite;

import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.boot.starter.sharding.core.ShardContextTaskDecorator;
import org.springframework.core.task.TaskDecorator;

/**
 * Extension of {@link ShardContextTaskDecorator} that also propagates the read-only
 * flag from the calling thread to the async worker thread.
 *
 * <p>Register this in place of {@link ShardContextTaskDecorator} when read/write
 * splitting is active:
 * <pre>{@code
 * executor.setTaskDecorator(new ReplicaAwareShardContextTaskDecorator());
 * }</pre>
 */
public class ReplicaAwareShardContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Long shardKey = ShardContext.get();
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

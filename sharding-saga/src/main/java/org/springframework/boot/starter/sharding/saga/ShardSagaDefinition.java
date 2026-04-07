package org.springframework.boot.starter.sharding.saga;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered list of {@link ShardSagaStep steps} that make up a cross-shard saga.
 *
 * <p>Build a definition once (at bean creation time) and reuse it for every saga run:
 * <pre>{@code
 * ShardSagaDefinition<TransferContext> transfer = ShardSagaDefinition.<TransferContext>builder()
 *     .step(new DebitSourceStep())
 *     .step(new CreditTargetStep())
 *     .step(new RecordLedgerStep())
 *     .build();
 *
 * orchestrator.execute("txn-" + UUID.randomUUID(), transfer, ctx);
 * }</pre>
 *
 * @param <T> shared context type threaded through every step
 */
public final class ShardSagaDefinition<T> {

    private final List<ShardSagaStep<T>> steps;

    private ShardSagaDefinition(List<ShardSagaStep<T>> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("A saga must contain at least one step");
        }
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /** Ordered step list (immutable). */
    public List<ShardSagaStep<T>> steps() {
        return steps;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static final class Builder<T> {
        private final List<ShardSagaStep<T>> steps = new ArrayList<>();

        public Builder<T> step(ShardSagaStep<T> step) {
            steps.add(step);
            return this;
        }

        public ShardSagaDefinition<T> build() {
            return new ShardSagaDefinition<>(steps);
        }
    }
}

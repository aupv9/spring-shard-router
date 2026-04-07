package org.springframework.boot.starter.sharding.saga;

/**
 * A single step in a cross-shard Saga.
 *
 * <p>Each step defines a forward action ({@link #execute}) and a compensating action
 * ({@link #compensate}) that reverses the effect of {@code execute} when a later step fails.
 * Compensation is invoked in reverse order by {@link ShardSagaOrchestrator} during rollback.
 *
 * <p>Steps should be idempotent: the orchestrator may retry a step or its compensation
 * if transient errors occur.
 *
 * @param <T> type of context object passed between steps
 */
public interface ShardSagaStep<T> {

    /** Human-readable name used in log entries and exceptions. */
    String name();

    /**
     * Execute the forward action for this step.
     *
     * @param context shared mutable context object (can carry results to later steps)
     * @throws Exception if execution fails; the orchestrator will begin compensation
     */
    void execute(T context) throws Exception;

    /**
     * Compensate (undo) a previously successful {@link #execute}.
     *
     * <p>Called only if a <em>later</em> step fails. Implementations must be
     * idempotent — the orchestrator may call this more than once.
     *
     * @param context the same context object passed to {@link #execute}
     */
    void compensate(T context);
}

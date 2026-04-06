package org.springframework.boot.starter.sharding.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a {@link ShardSagaDefinition} and automatically compensates completed steps
 * when any step fails.
 *
 * <h2>Execution model</h2>
 * <ol>
 *   <li>Steps are executed in definition order.</li>
 *   <li>If step {@code N} throws, compensation runs for steps {@code N-1} down to {@code 0}
 *       (reverse order, skipping the failed step itself because its effect was never applied).</li>
 *   <li>Compensation failures are logged but do not stop the compensation loop — the
 *       orchestrator always attempts to compensate every previously-succeeded step.</li>
 *   <li>A {@link ShardSagaException} is thrown carrying the full audit log.</li>
 * </ol>
 *
 * <p>Enable via {@code sharding.saga.enabled=true} or wire the bean manually.
 */
public class ShardSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ShardSagaOrchestrator.class);

    /**
     * Execute all steps in a saga.  If any step fails, the orchestrator compensates all
     * previously completed steps in reverse order, then throws {@link ShardSagaException}.
     *
     * @param sagaId     unique identifier for this run (UUID recommended)
     * @param definition the ordered saga definition
     * @param context    mutable context threaded through every step
     * @param <T>        context type
     * @throws ShardSagaException if any step fails (compensation has already been attempted)
     */
    public <T> void execute(String sagaId, ShardSagaDefinition<T> definition, T context) {
        List<ShardSagaStep<T>> steps = definition.steps();
        List<ShardSagaLog> auditLog = new ArrayList<>();
        int lastCompletedIndex = -1;

        // --- Forward pass ---
        for (int i = 0; i < steps.size(); i++) {
            ShardSagaStep<T> step = steps.get(i);
            try {
                log.debug("[saga:{}] executing step [{}]", sagaId, step.name());
                step.execute(context);
                auditLog.add(ShardSagaLog.executeSuccess(sagaId, step.name()));
                lastCompletedIndex = i;
            } catch (Exception ex) {
                log.warn("[saga:{}] step [{}] failed: {}", sagaId, step.name(), ex.getMessage());
                auditLog.add(ShardSagaLog.executeFailure(sagaId, step.name(), ex));

                // --- Compensation pass (reverse order, skip failed step) ---
                compensate(sagaId, steps, lastCompletedIndex, context, auditLog);

                throw new ShardSagaException(sagaId,
                    "Saga " + sagaId + " failed at step [" + step.name() + "]", ex, auditLog);
            }
        }

        log.info("[saga:{}] completed successfully ({} steps)", sagaId, steps.size());
    }

    private <T> void compensate(String sagaId,
                                 List<ShardSagaStep<T>> steps,
                                 int lastCompletedIndex,
                                 T context,
                                 List<ShardSagaLog> auditLog) {
        for (int i = lastCompletedIndex; i >= 0; i--) {
            ShardSagaStep<T> step = steps.get(i);
            try {
                log.info("[saga:{}] compensating step [{}]", sagaId, step.name());
                step.compensate(context);
                auditLog.add(ShardSagaLog.compensateSuccess(sagaId, step.name()));
            } catch (Exception ex) {
                // Log and continue — we must attempt all compensations
                log.error("[saga:{}] compensation of step [{}] failed: {}",
                    sagaId, step.name(), ex.getMessage(), ex);
                auditLog.add(ShardSagaLog.compensateFailure(sagaId, step.name(), ex));
            }
        }
    }
}

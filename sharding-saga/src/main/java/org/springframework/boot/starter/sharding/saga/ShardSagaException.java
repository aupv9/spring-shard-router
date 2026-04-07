package org.springframework.boot.starter.sharding.saga;

import java.util.List;

/**
 * Thrown by {@link ShardSagaOrchestrator} when a saga fails and compensation has been
 * attempted.  The exception carries the full audit log so callers can inspect exactly
 * which steps succeeded, which failed, and whether any compensation steps also failed.
 */
public class ShardSagaException extends RuntimeException {

    private final String sagaId;
    private final List<ShardSagaLog> auditLog;

    public ShardSagaException(String sagaId, String message, Throwable cause,
                               List<ShardSagaLog> auditLog) {
        super(message, cause);
        this.sagaId = sagaId;
        this.auditLog = List.copyOf(auditLog);
    }

    /** The unique run identifier for the failed saga. */
    public String getSagaId() {
        return sagaId;
    }

    /**
     * Complete audit trail: one {@link ShardSagaLog} entry per step execution or
     * compensation attempt, in chronological order.
     */
    public List<ShardSagaLog> getAuditLog() {
        return auditLog;
    }
}

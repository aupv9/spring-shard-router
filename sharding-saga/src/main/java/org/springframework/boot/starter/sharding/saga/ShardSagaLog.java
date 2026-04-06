package org.springframework.boot.starter.sharding.saga;

import java.time.Instant;

/**
 * Immutable audit record for a single step execution within a saga run.
 *
 * @param sagaId    identifier of the saga run (UUID)
 * @param stepName  {@link ShardSagaStep#name()} value
 * @param phase     EXECUTE or COMPENSATE
 * @param status    SUCCESS or FAILURE
 * @param timestamp when this entry was recorded
 * @param errorMsg  exception message when status is FAILURE, otherwise {@code null}
 */
public record ShardSagaLog(
    String sagaId,
    String stepName,
    Phase phase,
    Status status,
    Instant timestamp,
    String errorMsg
) {

    public enum Phase  { EXECUTE, COMPENSATE }
    public enum Status { SUCCESS, FAILURE }

    public static ShardSagaLog executeSuccess(String sagaId, String stepName) {
        return new ShardSagaLog(sagaId, stepName, Phase.EXECUTE, Status.SUCCESS, Instant.now(), null);
    }

    public static ShardSagaLog executeFailure(String sagaId, String stepName, Throwable ex) {
        return new ShardSagaLog(sagaId, stepName, Phase.EXECUTE, Status.FAILURE,
            Instant.now(), ex.getMessage());
    }

    public static ShardSagaLog compensateSuccess(String sagaId, String stepName) {
        return new ShardSagaLog(sagaId, stepName, Phase.COMPENSATE, Status.SUCCESS, Instant.now(), null);
    }

    public static ShardSagaLog compensateFailure(String sagaId, String stepName, Throwable ex) {
        return new ShardSagaLog(sagaId, stepName, Phase.COMPENSATE, Status.FAILURE,
            Instant.now(), ex.getMessage());
    }
}

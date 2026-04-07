package org.springframework.boot.starter.sharding.saga;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ShardSagaOrchestrator}.
 */
class ShardSagaOrchestratorTest {

    private final ShardSagaOrchestrator orchestrator = new ShardSagaOrchestrator();

    // -------------------------------------------------------------------------
    // Happy-path
    // -------------------------------------------------------------------------

    @Test
    void allStepsSucceed_noCompensation() {
        List<String> trace = new ArrayList<>();

        ShardSagaDefinition<List<String>> def = ShardSagaDefinition.<List<String>>builder()
            .step(recordingStep("step-A", false, trace))
            .step(recordingStep("step-B", false, trace))
            .step(recordingStep("step-C", false, trace))
            .build();

        orchestrator.execute("saga-1", def, trace);

        assertThat(trace).containsExactly("exec:step-A", "exec:step-B", "exec:step-C");
    }

    // -------------------------------------------------------------------------
    // Compensation
    // -------------------------------------------------------------------------

    @Test
    void secondStepFails_compensatesFirstStep() {
        List<String> trace = new ArrayList<>();

        ShardSagaDefinition<List<String>> def = ShardSagaDefinition.<List<String>>builder()
            .step(recordingStep("step-A", false, trace))
            .step(recordingStep("step-B", true,  trace))  // fails
            .build();

        assertThatThrownBy(() -> orchestrator.execute("saga-2", def, trace))
            .isInstanceOf(ShardSagaException.class)
            .satisfies(e -> {
                ShardSagaException ex = (ShardSagaException) e;
                assertThat(ex.getSagaId()).isEqualTo("saga-2");
                assertThat(ex.getAuditLog()).hasSize(3);
            });

        // step-A executed and compensated; step-B only executed (failed)
        assertThat(trace).containsExactly("exec:step-A", "exec:step-B", "comp:step-A");
    }

    @Test
    void thirdStepFails_compensatesInReverseOrder() {
        List<String> trace = new ArrayList<>();

        ShardSagaDefinition<List<String>> def = ShardSagaDefinition.<List<String>>builder()
            .step(recordingStep("step-A", false, trace))
            .step(recordingStep("step-B", false, trace))
            .step(recordingStep("step-C", true,  trace))  // fails
            .build();

        assertThatThrownBy(() -> orchestrator.execute("saga-3", def, trace))
            .isInstanceOf(ShardSagaException.class);

        assertThat(trace).containsExactly(
            "exec:step-A", "exec:step-B", "exec:step-C",
            "comp:step-B", "comp:step-A"   // reverse order, C skipped (never completed)
        );
    }

    @Test
    void firstStepFails_noCompensationNeeded() {
        List<String> trace = new ArrayList<>();

        ShardSagaDefinition<List<String>> def = ShardSagaDefinition.<List<String>>builder()
            .step(recordingStep("step-A", true, trace))   // fails immediately
            .step(recordingStep("step-B", false, trace))
            .build();

        assertThatThrownBy(() -> orchestrator.execute("saga-4", def, trace))
            .isInstanceOf(ShardSagaException.class);

        assertThat(trace).containsExactly("exec:step-A");
    }

    // -------------------------------------------------------------------------
    // Audit log
    // -------------------------------------------------------------------------

    @Test
    void auditLog_recordsAllEvents() {
        List<String> trace = new ArrayList<>();

        ShardSagaDefinition<List<String>> def = ShardSagaDefinition.<List<String>>builder()
            .step(recordingStep("debit",  false, trace))
            .step(recordingStep("credit", true,  trace))  // fails
            .build();

        ShardSagaException ex = catchThrowableOfType(
            () -> orchestrator.execute("saga-5", def, trace),
            ShardSagaException.class);

        List<ShardSagaLog> log = ex.getAuditLog();
        assertThat(log).hasSize(3);
        assertThat(log.get(0).stepName()).isEqualTo("debit");
        assertThat(log.get(0).phase()).isEqualTo(ShardSagaLog.Phase.EXECUTE);
        assertThat(log.get(0).status()).isEqualTo(ShardSagaLog.Status.SUCCESS);

        assertThat(log.get(1).stepName()).isEqualTo("credit");
        assertThat(log.get(1).phase()).isEqualTo(ShardSagaLog.Phase.EXECUTE);
        assertThat(log.get(1).status()).isEqualTo(ShardSagaLog.Status.FAILURE);

        assertThat(log.get(2).stepName()).isEqualTo("debit");
        assertThat(log.get(2).phase()).isEqualTo(ShardSagaLog.Phase.COMPENSATE);
        assertThat(log.get(2).status()).isEqualTo(ShardSagaLog.Status.SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Definition validation
    // -------------------------------------------------------------------------

    @Test
    void emptyDefinition_throwsIllegalArgument() {
        assertThatThrownBy(() -> ShardSagaDefinition.builder().build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private ShardSagaStep<List<String>> recordingStep(String name, boolean failOnExecute,
                                                       List<String> trace) {
        return new ShardSagaStep<>() {
            @Override public String name() { return name; }

            @Override
            public void execute(List<String> ctx) throws Exception {
                ctx.add("exec:" + name);
                if (failOnExecute) throw new RuntimeException("Forced failure in " + name);
            }

            @Override
            public void compensate(List<String> ctx) {
                ctx.add("comp:" + name);
            }
        };
    }
}

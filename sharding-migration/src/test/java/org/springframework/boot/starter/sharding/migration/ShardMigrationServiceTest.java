package org.springframework.boot.starter.sharding.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardRouter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShardMigrationService} state machine transitions.
 */
class ShardMigrationServiceTest {

    private ShardRouter shardRouter;
    private ShardMigrationService service;

    @BeforeEach
    void setUp() {
        shardRouter  = mock(ShardRouter.class);
        when(shardRouter.getShardCount()).thenReturn(3);

        service = new ShardMigrationService(shardRouter);
    }

    // -------------------------------------------------------------------------
    // startMigration
    // -------------------------------------------------------------------------

    @Test
    void startMigration_createsDoubleWritingPlan() {
        ShardMigrationPlan plan = service.startMigration(1L, 1000L, 0, 1, 500);

        assertThat(plan.migrationId()).isNotBlank();
        assertThat(plan.state()).isEqualTo(MigrationState.DOUBLE_WRITING);
        assertThat(plan.sourceShard()).isEqualTo(0);
        assertThat(plan.targetShard()).isEqualTo(1);
        assertThat(plan.keyMin()).isEqualTo(1L);
        assertThat(plan.keyMax()).isEqualTo(1000L);
    }

    @Test
    void startMigration_sameShard_throws() {
        assertThatThrownBy(() -> service.startMigration(1L, 100L, 0, 0, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("differ");
    }

    @Test
    void startMigration_invalidShardIndex_throws() {
        assertThatThrownBy(() -> service.startMigration(1L, 100L, 0, 99, 100))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // isInActiveMigration
    // -------------------------------------------------------------------------

    @Test
    void isInActiveMigration_keyInRange_returnsTrue() {
        service.startMigration(100L, 200L, 0, 1, 50);

        assertThat(service.isInActiveMigration(150L)).isTrue();
        assertThat(service.isInActiveMigration(100L)).isTrue();
        assertThat(service.isInActiveMigration(200L)).isTrue();
    }

    @Test
    void isInActiveMigration_keyOutsideRange_returnsFalse() {
        service.startMigration(100L, 200L, 0, 1, 50);

        assertThat(service.isInActiveMigration(99L)).isFalse();
        assertThat(service.isInActiveMigration(201L)).isFalse();
    }

    // -------------------------------------------------------------------------
    // State machine: requirePlan validates state
    // -------------------------------------------------------------------------

    @Test
    void cutover_withoutBackfill_throws() {
        ShardMigrationPlan plan = service.startMigration(1L, 10L, 0, 1, 5);
        // Plan is in DOUBLE_WRITING, not READY_TO_CUTOVER
        assertThatThrownBy(() -> service.cutover(plan.migrationId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("READY_TO_CUTOVER");
    }

    @Test
    void rollback_unknownId_throws() {
        assertThatThrownBy(() -> service.rollback("unknown-id"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown-id");
    }

    @Test
    void rollback_fromDoubleWritingState_transitionsToRolledBack() {
        ShardMigrationPlan plan = service.startMigration(1L, 10L, 0, 1, 5);
        ShardMigrationPlan rolledBack = service.rollback(plan.migrationId());
        assertThat(rolledBack.state()).isEqualTo(MigrationState.ROLLED_BACK);
    }

    // -------------------------------------------------------------------------
    // getPlan / listPlans
    // -------------------------------------------------------------------------

    @Test
    void getPlan_unknownId_returnsNull() {
        assertThat(service.getPlan("no-such-id")).isNull();
    }

    @Test
    void listPlans_includesAllCreatedPlans() {
        service.startMigration(1L, 100L, 0, 1, 10);
        service.startMigration(101L, 200L, 1, 2, 10);

        assertThat(service.listPlans()).hasSize(2);
    }

    @Test
    void getProgress_returnsTracker() {
        ShardMigrationPlan plan = service.startMigration(1L, 10L, 0, 1, 5);
        assertThat(service.getProgress(plan.migrationId())).isNotNull();
    }

    @Test
    void findActivePlanFor_returnsMatchingPlan() {
        ShardMigrationPlan plan = service.startMigration(100L, 200L, 0, 1, 10);
        ShardMigrationPlan found = service.findActivePlanFor(150L);
        assertThat(found).isNotNull();
        assertThat(found.migrationId()).isEqualTo(plan.migrationId());
    }

    @Test
    void findActivePlanFor_noMatch_returnsNull() {
        service.startMigration(100L, 200L, 0, 1, 10);
        assertThat(service.findActivePlanFor(999L)).isNull();
    }
}

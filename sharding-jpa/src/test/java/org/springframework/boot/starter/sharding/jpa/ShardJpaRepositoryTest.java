package org.springframework.boot.starter.sharding.jpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the default methods in {@link ShardJpaRepository}.
 *
 * <p>Uses a minimal anonymous concrete implementation backed by a Mockito spy to
 * verify that:
 * <ul>
 *   <li>Every shard-aware default method delegates to its no-shardKey counterpart</li>
 *   <li>ShardContext is set before delegation and restored/cleared after</li>
 *   <li>Outer key is preserved when a shard-aware call is nested inside another
 *       shard-aware scope (Gap 2.1 fix)</li>
 *   <li>RuntimeExceptions are re-thrown unchanged and context is still restored</li>
 * </ul>
 */
class ShardJpaRepositoryTest {

    /** Minimal entity used for type parameters. */
    static class Widget {
        Long id;
        Widget(Long id) { this.id = id; }
    }

    /**
     * Concrete test repository that delegates every Spring Data operation to Mockito mocks.
     * The default methods from {@link ShardJpaRepository} are what we are actually testing.
     */
    interface WidgetRepository extends ShardJpaRepository<Widget, Long> {}

    private WidgetRepository repo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Create a Mockito mock of the interface; the default methods in ShardJpaRepository
        // will execute as real code (CALLS_REAL_METHODS not needed — default methods in
        // interfaces run natively through the proxy).
        repo = mock(WidgetRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        ShardContext.clear();
    }

    @AfterEach
    void tearDown() {
        ShardContext.clear();
    }

    // -------------------------------------------------------------------------
    // findById — delegating default method
    // -------------------------------------------------------------------------

    @Test
    void findById_setsContextAndDelegates() {
        Widget w = new Widget(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(w));

        Optional<Widget> result = repo.findById(10L, 1L);

        assertThat(result).contains(w);
        verify(repo).findById(1L);
    }

    @Test
    void findById_contextSetDuringDelegate() {
        long shardKey = 42L;
        AtomicReference<Long> capturedKey = new AtomicReference<>();

        when(repo.findById(1L)).thenAnswer(inv -> {
            capturedKey.set(ShardContext.get());
            return Optional.empty();
        });

        repo.findById(shardKey, 1L);

        assertThat(capturedKey.get()).isEqualTo(shardKey);
    }

    // -------------------------------------------------------------------------
    // save
    // -------------------------------------------------------------------------

    @Test
    void save_delegatesAndSetsContext() {
        Widget w = new Widget(5L);
        when(repo.save(w)).thenReturn(w);

        Widget result = repo.save(50L, w);

        assertThat(result).isSameAs(w);
        verify(repo).save(w);
    }

    // -------------------------------------------------------------------------
    // deleteById
    // -------------------------------------------------------------------------

    @Test
    void deleteById_delegatesWithCorrectContext() {
        AtomicReference<Long> capturedKey = new AtomicReference<>();
        doAnswer(inv -> { capturedKey.set(ShardContext.get()); return null; })
            .when(repo).deleteById(99L);

        repo.deleteById(77L, 99L);

        assertThat(capturedKey.get()).isEqualTo(77L);
        verify(repo).deleteById(99L);
    }

    // -------------------------------------------------------------------------
    // count
    // -------------------------------------------------------------------------

    @Test
    void count_delegatesAndReturnsValue() {
        when(repo.count()).thenReturn(7L);

        long result = repo.count(100L);

        assertThat(result).isEqualTo(7L);
    }

    // -------------------------------------------------------------------------
    // findAll with Pageable
    // -------------------------------------------------------------------------

    @Test
    void findAllPageable_delegatesAndSetsContext() {
        PageRequest pageable = PageRequest.of(0, 10);
        Page<Widget> page = new PageImpl<>(List.of(new Widget(1L)));
        when(repo.findAll(pageable)).thenReturn(page);

        Page<Widget> result = repo.findAll(100L, pageable);

        assertThat(result).isSameAs(page);
    }

    // -------------------------------------------------------------------------
    // existsById
    // -------------------------------------------------------------------------

    @Test
    void existsById_delegatesCorrectly() {
        when(repo.existsById(3L)).thenReturn(true);

        assertThat(repo.existsById(200L, 3L)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Context save/restore (Gap 2.1)
    // -------------------------------------------------------------------------

    @Test
    void contextClearedAfterCall_noOuterKey() {
        when(repo.findById(1L)).thenReturn(Optional.empty());

        repo.findById(10L, 1L);

        assertThat(ShardContext.get())
            .as("ShardContext must be null after call when no outer key was set")
            .isNull();
    }

    @Test
    void outerKeyRestoredAfterNestedCall() {
        long outerKey = 88L;
        ShardContext.set(outerKey);
        when(repo.findById(1L)).thenReturn(Optional.empty());

        repo.findById(99L, 1L); // nested call with different shardKey

        assertThat(ShardContext.get())
            .as("Outer ShardContext key must be restored after nested call")
            .isEqualTo(outerKey);
    }

    @Test
    void multipleSequentialCalls_contextRestoredBetweenEachCall() {
        when(repo.count()).thenReturn(0L);

        repo.count(10L);
        assertThat(ShardContext.get()).isNull();

        repo.count(20L);
        assertThat(ShardContext.get()).isNull();

        repo.count(30L);
        assertThat(ShardContext.get()).isNull();
    }

    // -------------------------------------------------------------------------
    // Exception propagation
    // -------------------------------------------------------------------------

    @Test
    void exceptionFromDelegate_rethrownUnchanged() {
        IllegalStateException ex = new IllegalStateException("detached entity");
        when(repo.save(any())).thenThrow(ex);

        assertThatThrownBy(() -> repo.save(10L, new Widget(1L)))
            .isSameAs(ex);
    }

    @Test
    void contextClearedEvenWhenDelegateThrows() {
        when(repo.save(any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> repo.save(10L, new Widget(1L)))
            .isInstanceOf(RuntimeException.class);

        assertThat(ShardContext.get())
            .as("ShardContext must be null even after delegate throws")
            .isNull();
    }

    @Test
    void outerKeyRestoredEvenWhenDelegateThrows() {
        long outerKey = 123L;
        ShardContext.set(outerKey);
        when(repo.save(any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> repo.save(999L, new Widget(1L)))
            .isInstanceOf(RuntimeException.class);

        assertThat(ShardContext.get())
            .as("Outer key must be restored even after delegate throws")
            .isEqualTo(outerKey);
    }

    // -------------------------------------------------------------------------
    // executeWithShardKey — direct coverage
    // -------------------------------------------------------------------------

    @Test
    void executeWithShardKey_returnsValueFromOperation() {
        long result = repo.executeWithShardKey(77L, () -> 42L);

        assertThat(result).isEqualTo(42L);
        assertThat(ShardContext.get()).isNull();
    }

    @Test
    void executeWithShardKey_setsKeyDuringOperation() {
        long shardKey = 55L;
        AtomicReference<Long> captured = new AtomicReference<>();

        repo.executeWithShardKey(shardKey, () -> {
            captured.set(ShardContext.get());
            return null;
        });

        assertThat(captured.get()).isEqualTo(shardKey);
    }
}

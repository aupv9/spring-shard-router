package org.springframework.boot.starter.sharding.jpa;

import jakarta.persistence.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.starter.sharding.core.ShardContext;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShardEntityManager}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Each public operation delegates to the underlying {@link EntityManager}</li>
 *   <li>ShardContext is set before the delegate call and restored/cleared after</li>
 *   <li>Context save/restore: outer key is preserved (Gap 2.1 fix)</li>
 *   <li>Exception propagation: RuntimeException re-thrown unchanged</li>
 *   <li>Context cleared even when delegate throws</li>
 * </ul>
 */
class ShardEntityManagerTest {

    private EntityManager delegate;
    private ShardEntityManager shardEm;

    @BeforeEach
    void setUp() {
        delegate = mock(EntityManager.class);
        shardEm = new ShardEntityManager(delegate);
        ShardContext.clear();
    }

    @AfterEach
    void tearDown() {
        ShardContext.clear();
    }

    // -------------------------------------------------------------------------
    // Delegation — each operation reaches the delegate
    // -------------------------------------------------------------------------

    @Test
    void find_delegatesToEntityManager() {
        Object entity = new Object();
        when(delegate.find(Object.class, 1L)).thenReturn(entity);

        Object result = shardEm.find(100L, Object.class, 1L);

        assertThat(result).isSameAs(entity);
        verify(delegate).find(Object.class, 1L);
    }

    @Test
    void persist_delegatesToEntityManager() {
        Object entity = new Object();

        shardEm.persist(100L, entity);

        verify(delegate).persist(entity);
    }

    @Test
    void merge_delegatesToEntityManager() {
        Object entity = new Object();
        Object merged = new Object();
        when(delegate.merge(entity)).thenReturn(merged);

        Object result = shardEm.merge(100L, entity);

        assertThat(result).isSameAs(merged);
        verify(delegate).merge(entity);
    }

    @Test
    void remove_delegatesToEntityManager() {
        Object entity = new Object();

        shardEm.remove(100L, entity);

        verify(delegate).remove(entity);
    }

    @Test
    void createQuery_delegatesToEntityManager() {
        Query query = mock(Query.class);
        when(delegate.createQuery("SELECT a FROM Account a")).thenReturn(query);

        Query result = shardEm.createQuery(100L, "SELECT a FROM Account a");

        assertThat(result).isSameAs(query);
    }

    @Test
    void createTypedQuery_delegatesToEntityManager() {
        TypedQuery<Object> query = mock(TypedQuery.class);
        when(delegate.createQuery("SELECT a FROM Account a", Object.class)).thenReturn(query);

        TypedQuery<Object> result = shardEm.createQuery(100L, "SELECT a FROM Account a", Object.class);

        assertThat(result).isSameAs(query);
    }

    @Test
    void createNativeQuery_delegatesToEntityManager() {
        Query query = mock(Query.class);
        when(delegate.createNativeQuery("SELECT 1")).thenReturn(query);

        Query result = shardEm.createNativeQuery(100L, "SELECT 1");

        assertThat(result).isSameAs(query);
    }

    @Test
    void getDelegate_returnsWrappedEntityManager() {
        assertThat(shardEm.getDelegate()).isSameAs(delegate);
    }

    // -------------------------------------------------------------------------
    // ShardContext is set during delegate call
    // -------------------------------------------------------------------------

    @Test
    void shardContextIsSetDuringDelegateCall() {
        long shardKey = 42L;
        AtomicReference<Long> capturedKey = new AtomicReference<>();

        doAnswer(inv -> {
            capturedKey.set(ShardContext.get());
            return null;
        }).when(delegate).persist(any());

        shardEm.persist(shardKey, new Object());

        assertThat(capturedKey.get())
            .as("ShardContext must equal the provided shardKey during the delegate call")
            .isEqualTo(shardKey);
    }

    // -------------------------------------------------------------------------
    // Context save/restore (Gap 2.1 fix)
    // -------------------------------------------------------------------------

    @Test
    void contextClearedAfterCall_whenNoOuterKey() {
        shardEm.persist(100L, new Object());

        assertThat(ShardContext.get())
            .as("ShardContext must be null after call when no outer key was set")
            .isNull();
    }

    @Test
    void outerKeyRestoredAfterNestedCall() {
        long outerKey = 99L;
        ShardContext.set(outerKey);

        shardEm.persist(100L, new Object()); // nested call with different key

        assertThat(ShardContext.get())
            .as("ShardContext must be restored to the outer key after nested call")
            .isEqualTo(outerKey);
    }

    @Test
    void multipleSequentialCalls_contextRestoredBetweenEach() {
        shardEm.persist(10L, new Object());
        assertThat(ShardContext.get()).isNull();

        shardEm.persist(20L, new Object());
        assertThat(ShardContext.get()).isNull();

        shardEm.persist(30L, new Object());
        assertThat(ShardContext.get()).isNull();
    }

    // -------------------------------------------------------------------------
    // Exception propagation
    // -------------------------------------------------------------------------

    @Test
    void runtimeExceptionFromDelegate_rethrownUnchanged() {
        IllegalStateException ex = new IllegalStateException("detached entity");
        doThrow(ex).when(delegate).persist(any());

        assertThatThrownBy(() -> shardEm.persist(100L, new Object()))
            .isSameAs(ex)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void contextClearedEvenWhenDelegateThrows() {
        doThrow(new RuntimeException("failure")).when(delegate).persist(any());

        assertThatThrownBy(() -> shardEm.persist(100L, new Object()))
            .isInstanceOf(RuntimeException.class);

        assertThat(ShardContext.get())
            .as("ShardContext must be null even after delegate throws")
            .isNull();
    }

    @Test
    void outerKeyRestoredEvenWhenDelegateThrows() {
        long outerKey = 55L;
        ShardContext.set(outerKey);

        doThrow(new RuntimeException("failure")).when(delegate).persist(any());

        assertThatThrownBy(() -> shardEm.persist(100L, new Object()))
            .isInstanceOf(RuntimeException.class);

        assertThat(ShardContext.get())
            .as("Outer ShardContext key must be restored even after delegate throws")
            .isEqualTo(outerKey);
    }

    // -------------------------------------------------------------------------
    // Non-sharded operations delegate without touching ShardContext
    // -------------------------------------------------------------------------

    @Test
    void flush_delegatesAndDoesNotTouchShardContext() {
        ShardContext.set(77L);

        shardEm.flush();

        verify(delegate).flush();
        assertThat(ShardContext.get()).isEqualTo(77L); // unchanged
    }

    @Test
    void clear_delegatesAndDoesNotTouchShardContext() {
        ShardContext.set(77L);

        shardEm.clear();

        verify(delegate).clear();
        assertThat(ShardContext.get()).isEqualTo(77L);
    }

    @Test
    void isOpen_delegatesToEntityManager() {
        when(delegate.isOpen()).thenReturn(true);
        assertThat(shardEm.isOpen()).isTrue();
    }
}

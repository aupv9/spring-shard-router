package org.springframework.boot.starter.sharding.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.starter.sharding.core.MissingShardKeyException;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.boot.starter.sharding.core.ShardRouter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RoutingDataSource}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Correct shard selection when a key is set</li>
 *   <li>Startup-phase shard-0 fallback (fallbackAllowed=true)</li>
 *   <li>Runtime-phase {@link MissingShardKeyException} (fallbackAllowed=false)</li>
 *   <li>Thread-isolation of the shard key</li>
 * </ul>
 */
class RoutingDataSourceTest {

    private DataSource ds0;
    private DataSource ds1;
    private DataSource ds2;
    private ShardRouter shardRouter;
    private RoutingDataSource routingDataSource;

    @BeforeEach
    void setUp() throws SQLException {
        ds0 = mockDataSource("ds0");
        ds1 = mockDataSource("ds1");
        ds2 = mockDataSource("ds2");

        shardRouter = mock(ShardRouter.class);
        when(shardRouter.getShardCount()).thenReturn(3);
        when(shardRouter.getShard(0)).thenReturn(Shard.of("shard-0", 0, ds0));
        when(shardRouter.getShard(1)).thenReturn(Shard.of("shard-1", 1, ds1));
        when(shardRouter.getShard(2)).thenReturn(Shard.of("shard-2", 2, ds2));
        when(shardRouter.resolve(anyLong())).thenAnswer(inv -> {
            long key = inv.getArgument(0);
            // Simple modulo routing for tests
            int idx = (int) Math.abs(key % 3);
            return shardRouter.getShard(idx);
        });

        routingDataSource = new RoutingDataSource(shardRouter);
        // Ensure fallback enabled (startup mode) by default
        ShardContext.enableFallback();
    }

    @AfterEach
    void tearDown() {
        ShardContext.clear();
        ShardContext.enableFallback(); // always reset to safe state after each test
    }

    // -------------------------------------------------------------------------
    // Key-present routing
    // -------------------------------------------------------------------------

    @Test
    void getConnection_withShardKey_routesToCorrectShard() throws SQLException {
        ShardContext.set(0L); // key 0 → shard 0 (0 % 3 == 0)

        Connection conn = routingDataSource.getConnection();

        assertThat(conn).isSameAs(ds0.getConnection());
    }

    @Test
    void getConnection_withDifferentKey_routesToDifferentShard() throws SQLException {
        ShardContext.set(1L); // key 1 → shard 1 (1 % 3 == 1)

        Connection conn = routingDataSource.getConnection();

        assertThat(conn).isSameAs(ds1.getConnection());
    }

    @Test
    void getConnection_withUsernamePassword_routesCorrectly() throws SQLException {
        ShardContext.set(2L); // 2 % 3 == 2

        Connection conn = routingDataSource.getConnection("user", "pass");

        assertThat(conn).isSameAs(ds2.getConnection("user", "pass"));
    }

    // -------------------------------------------------------------------------
    // Startup-phase fallback (fallbackAllowed = true)
    // -------------------------------------------------------------------------

    @Test
    void getConnection_noKeySet_fallbackAllowed_returnsShardZero() throws SQLException {
        // ShardContext is empty, fallback is enabled (startup mode)
        assertThat(ShardContext.get()).isNull();
        assertThat(ShardContext.isFallbackAllowed()).isTrue();

        Connection conn = routingDataSource.getConnection();

        assertThat(conn).isSameAs(ds0.getConnection());
        verify(shardRouter).getShard(0); // fallback path must use getShard(0)
        verify(shardRouter, never()).resolve(anyLong());
    }

    // -------------------------------------------------------------------------
    // Runtime-phase: throw when no key and fallback disabled
    // -------------------------------------------------------------------------

    @Test
    void getConnection_noKeySet_fallbackDisabled_throwsMissingShardKeyException() {
        ShardContext.disableFallback();
        assertThat(ShardContext.get()).isNull();

        assertThatThrownBy(() -> routingDataSource.getConnection())
            .isInstanceOf(MissingShardKeyException.class)
            .hasMessageContaining("ShardJdbcTemplate")
            .hasMessageContaining("@ShardBy");
    }

    @Test
    void getConnection_withUsernamePassword_noKeySet_fallbackDisabled_throws() {
        ShardContext.disableFallback();

        assertThatThrownBy(() -> routingDataSource.getConnection("u", "p"))
            .isInstanceOf(MissingShardKeyException.class);
    }

    // -------------------------------------------------------------------------
    // getShardRouter accessor
    // -------------------------------------------------------------------------

    @Test
    void getShardRouter_returnsConfiguredRouter() {
        assertThat(routingDataSource.getShardRouter()).isSameAs(shardRouter);
    }

    // -------------------------------------------------------------------------
    // Thread isolation
    // -------------------------------------------------------------------------

    @Test
    void getConnection_keyIsThreadLocal_differentThreadsRouteIndependently()
            throws InterruptedException, SQLException {

        ShardContext.set(0L); // main thread → shard 0

        Connection[] threadConn = new Connection[1];
        Thread worker = new Thread(() -> {
            try {
                ShardContext.set(1L); // worker → shard 1
                threadConn[0] = routingDataSource.getConnection();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } finally {
                ShardContext.clear();
            }
        });
        worker.start();
        worker.join();

        // Worker got shard-1's connection
        assertThat(threadConn[0]).isSameAs(ds1.getConnection());
        // Main thread routing unchanged
        assertThat(routingDataSource.getConnection()).isSameAs(ds0.getConnection());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static DataSource mockDataSource(String name) throws SQLException {
        DataSource ds = mock(DataSource.class, name);
        Connection conn = mock(Connection.class, name + "-conn");
        when(ds.getConnection()).thenReturn(conn);
        when(ds.getConnection(anyString(), anyString())).thenReturn(conn);
        return ds;
    }
}

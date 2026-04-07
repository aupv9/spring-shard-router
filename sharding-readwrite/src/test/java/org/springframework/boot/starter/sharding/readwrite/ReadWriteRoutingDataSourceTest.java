package org.springframework.boot.starter.sharding.readwrite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.starter.sharding.core.Shard;
import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.boot.starter.sharding.core.ShardRouter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReadWriteRoutingDataSource}.
 */
class ReadWriteRoutingDataSourceTest {

    private DataSource primary0;
    private DataSource replica0a;
    private DataSource replica0b;
    private ShardRouter shardRouter;
    private ReadWriteRoutingDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        primary0  = mockDataSource("primary-0");
        replica0a = mockDataSource("replica-0a");
        replica0b = mockDataSource("replica-0b");

        Shard shardWithReplicas = Shard.withReplicas("s0", 0, primary0,
            List.of(replica0a, replica0b));

        shardRouter = mock(ShardRouter.class);
        when(shardRouter.getShardCount()).thenReturn(1);
        when(shardRouter.resolve(anyLong())).thenReturn(shardWithReplicas);
        when(shardRouter.getShard(0)).thenReturn(shardWithReplicas);

        dataSource = new ReadWriteRoutingDataSource(shardRouter);
    }

    @AfterEach
    void tearDown() {
        ShardContext.clear();
    }

    @Test
    void noShardKey_fallsBackToPrimary() throws SQLException {
        // ShardContext is empty — should fall back to shard-0 primary
        Connection conn = dataSource.getConnection();
        assertThat(conn).isSameAs(primary0.getConnection());
    }

    @Test
    void writeOperation_usesPrimary() throws SQLException {
        ShardContext.set(42L);
        ShardContext.setReadOnly(false);

        Connection conn = dataSource.getConnection();
        assertThat(conn).isSameAs(primary0.getConnection());
    }

    @Test
    void readOperation_usesReplica() throws SQLException {
        ShardContext.set(42L);
        ShardContext.setReadOnly(true);

        Connection conn = dataSource.getConnection();
        // Must be one of the replicas, not the primary
        Connection primary = primary0.getConnection();
        assertThat(conn).isNotSameAs(primary);
    }

    @Test
    void replicaSelection_roundRobin() throws SQLException {
        ShardContext.set(42L);
        ShardContext.setReadOnly(true);

        Connection c1 = dataSource.getConnection();
        Connection c2 = dataSource.getConnection();
        // Two consecutive reads should cycle across the two replicas
        assertThat(c1).isNotSameAs(c2);
    }

    @Test
    void noReplicas_readOperationFallsBackToPrimary() throws SQLException {
        Shard primaryOnly = Shard.of("s1", 1, primary0);
        when(shardRouter.resolve(anyLong())).thenReturn(primaryOnly);
        ShardContext.set(99L);
        ShardContext.setReadOnly(true);

        Connection conn = dataSource.getConnection();
        assertThat(conn).isSameAs(primary0.getConnection());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private DataSource mockDataSource(String name) throws SQLException {
        DataSource ds = mock(DataSource.class, name);
        Connection conn = mock(Connection.class, name + "-conn");
        when(ds.getConnection()).thenReturn(conn);
        when(ds.getConnection(any(), any())).thenReturn(conn);
        return ds;
    }
}

package org.springframework.boot.starter.sharding.jdbc;

import org.springframework.boot.starter.sharding.core.ShardContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Shard-aware JdbcTemplate that automatically manages shard context
 * Provides finance-grade safety with automatic context cleanup
 */
public class ShardJdbcTemplate {
    
    private final JdbcTemplate jdbcTemplate;
    
    public ShardJdbcTemplate(DataSource routingDataSource) {
        this.jdbcTemplate = new JdbcTemplate(routingDataSource);
    }
    
    // UPDATE operations
    
    public int update(long shardKey, String sql, Object... args) {
        return executeWithShardKey(shardKey, "update", () -> jdbcTemplate.update(sql, args));
    }

    public int[] batchUpdate(long shardKey, String sql, List<Object[]> batchArgs) {
        return executeWithShardKey(shardKey, "batchUpdate", () ->
            jdbcTemplate.batchUpdate(sql, batchArgs));
    }

    // QUERY operations

    public <T> T queryForObject(long shardKey, String sql, Class<T> requiredType, Object... args) {
        return executeWithShardKey(shardKey, "queryForObject", () ->
            jdbcTemplate.queryForObject(sql, requiredType, args));
    }

    public <T> T queryForObject(long shardKey, String sql, RowMapper<T> rowMapper, Object... args) {
        return executeWithShardKey(shardKey, "queryForObject", () ->
            jdbcTemplate.queryForObject(sql, rowMapper, args));
    }

    public <T> List<T> query(long shardKey, String sql, RowMapper<T> rowMapper, Object... args) {
        return executeWithShardKey(shardKey, "query", () ->
            jdbcTemplate.query(sql, rowMapper, args));
    }

    public List<Map<String, Object>> queryForList(long shardKey, String sql, Object... args) {
        return executeWithShardKey(shardKey, "queryForList", () ->
            jdbcTemplate.queryForList(sql, args));
    }

    public Map<String, Object> queryForMap(long shardKey, String sql, Object... args) {
        return executeWithShardKey(shardKey, "queryForMap", () ->
            jdbcTemplate.queryForMap(sql, args));
    }

    public <T> T query(long shardKey, String sql, ResultSetExtractor<T> rse, Object... args) {
        return executeWithShardKey(shardKey, "query", () ->
            jdbcTemplate.query(sql, rse, args));
    }

    // EXECUTE operations

    public <T> T execute(long shardKey, String sql, PreparedStatementCallback<T> action) {
        return executeWithShardKey(shardKey, "execute", () ->
            jdbcTemplate.execute(sql, action));
    }

    // Core execution method with shard context management — protected for subclass instrumentation

    protected <T> T executeWithShardKey(long shardKey, String operation, ShardOperation<T> op) {
        try {
            ShardContext.set(shardKey);
            return op.execute();
        } catch (DataAccessException e) {
            // Re-throw Spring's DataAccessException as-is
            throw e;
        } catch (Exception e) {
            // Wrap other exceptions
            throw new DataAccessException("Shard operation failed for key: " + shardKey, e) {};
        } finally {
            ShardContext.clear();
        }
    }

    @FunctionalInterface
    protected interface ShardOperation<T> {
        T execute() throws Exception;
    }
    
    /**
     * Get underlying JdbcTemplate for advanced operations
     * Note: Shard context must be managed manually when using this
     */
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
}
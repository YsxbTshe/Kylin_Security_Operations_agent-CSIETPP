package com.superskidder.kylinops.backend.agentruntime.persistence;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ResourceLockStore {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final JdbcTemplate jdbcTemplate;

    public ResourceLockStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public long acquireWriteLock(String tenantId, String resourceKey, String runId, String attemptId) {
        Instant now = Instant.now();
        purgeExpired(tenantId, now);

        List<ExistingLock> existing = jdbcTemplate.query("""
                select lock_mode, held_by_run_id, lock_version from resource_lock
                where tenant_id = ? and resource_key = ? for update
                """, (rs, rowNum) -> new ExistingLock(rs.getString("lock_mode"),
                        rs.getString("held_by_run_id"), rs.getLong("lock_version")),
                tenantId, resourceKey);

        if (!existing.isEmpty()) {
            ExistingLock lock = existing.get(0);
            if (lock.runId().equals(runId)) {
                return lock.lockVersion();
            }
            throw new IllegalStateException("resource is locked: " + resourceKey);
        }

        long newVersion = nextLockVersion(tenantId, resourceKey);
        String id = "rlock-" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                insert into resource_lock (id, tenant_id, resource_key, lock_mode, held_by_run_id,
                    held_by_attempt_id, lock_version, acquired_at, expires_at)
                values (?, ?, ?, 'WRITE_EXCLUSIVE', ?, ?, ?, ?, ?)
                """, id, tenantId, resourceKey, runId, attemptId, newVersion,
                Timestamp.from(now), Timestamp.from(now.plus(DEFAULT_TTL)));
        return newVersion;
    }

    @Transactional
    public void releaseLock(String tenantId, String resourceKey, String runId) {
        jdbcTemplate.update("""
                delete from resource_lock where tenant_id = ? and resource_key = ? and held_by_run_id = ?
                """, tenantId, resourceKey, runId);
    }

    @Transactional
    public void releaseAllForRun(String tenantId, String runId) {
        jdbcTemplate.update("delete from resource_lock where tenant_id = ? and held_by_run_id = ?",
                tenantId, runId);
    }

    public long getCurrentLockVersion(String tenantId, String nodeId) {
        List<Long> versions = jdbcTemplate.query("""
                select lock_version from agent_runtime_node_lock_version
                where tenant_id = ? and node_id = ?
                """, (rs, rowNum) -> rs.getLong("lock_version"), tenantId, nodeId);
        return versions.isEmpty() ? 0L : versions.get(0);
    }

    @Transactional
    public long incrementNodeLockVersion(String tenantId, String nodeId) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                update agent_runtime_node_lock_version set lock_version = lock_version + 1, updated_at = ?
                where tenant_id = ? and node_id = ?
                """, Timestamp.from(now), tenantId, nodeId);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into agent_runtime_node_lock_version (tenant_id, node_id, lock_version, updated_at)
                    values (?, ?, 1, ?)
                    """, tenantId, nodeId, Timestamp.from(now));
        }
        return getCurrentLockVersion(tenantId, nodeId);
    }

    private void purgeExpired(String tenantId, Instant now) {
        jdbcTemplate.update("delete from resource_lock where tenant_id = ? and expires_at <= ?",
                tenantId, Timestamp.from(now));
    }

    private long nextLockVersion(String tenantId, String resourceKey) {
        // max() 聚合恒返回一行；无锁记录时该行为 SQL NULL，需按 null 处理（Optional.of(null) 会 NPE）。
        Long maxVersion = jdbcTemplate.queryForObject("""
                select max(lock_version) from resource_lock where tenant_id = ? and resource_key = ?
                """, Long.class, tenantId, resourceKey);
        return (maxVersion == null ? 0L : maxVersion) + 1;
    }

    private record ExistingLock(String lockMode, String runId, long lockVersion) {}
}

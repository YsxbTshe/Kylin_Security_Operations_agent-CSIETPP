package com.superskidder.kylinops.backend.agentruntime.artifact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ArtifactRepository {

    private final JdbcTemplate jdbcTemplate;

    public ArtifactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertWriting(ArtifactRecord record) {
        jdbcTemplate.update("""
                insert into agent_artifact (
                    id, tenant_id, agent_instance_id, name, content_type, size_bytes,
                    sm3_hex, status, storage_path, created_at, published_at, updated_at
                ) values (?, ?, ?, ?, ?, 0, null, 'WRITING', null, ?, null, ?)
                """, record.id(), record.tenantId(), record.agentInstanceId(), record.name(),
                record.contentType(), Timestamp.from(record.createdAt()), Timestamp.from(record.createdAt()));
    }

    public Optional<ArtifactRecord> findById(String tenantId, String id) {
        return jdbcTemplate.query("""
                select * from agent_artifact where tenant_id = ? and id = ?
                """, this::mapRow, tenantId, id).stream().findFirst();
    }

    /** GC 按磁盘目录反查记录时使用，租户取自目录名本身。 */
    public Optional<ArtifactRecord> findGlobalById(String id) {
        return jdbcTemplate.query("select * from agent_artifact where id = ?", this::mapRow, id)
                .stream().findFirst();
    }

    public Optional<ArtifactRecord> findPublishedByDigest(String tenantId, String sm3Hex) {
        return jdbcTemplate.query("""
                select * from agent_artifact
                where tenant_id = ? and sm3_hex = ? and status = 'PUBLISHED'
                order by published_at asc limit 1
                """, this::mapRow, tenantId, sm3Hex).stream().findFirst();
    }

    /** 仅允许 WRITING → PUBLISHED；命中唯一约束冲突时抛 DataAccessException，由调用方收敛为去重。 */
    public int publish(String tenantId, String id, long sizeBytes, String sm3Hex, String storagePath) {
        Instant now = Instant.now();
        return jdbcTemplate.update("""
                update agent_artifact set status = 'PUBLISHED', size_bytes = ?, sm3_hex = ?,
                    storage_path = ?, published_at = ?, updated_at = ?
                where tenant_id = ? and id = ? and status = 'WRITING'
                """, sizeBytes, sm3Hex, storagePath, Timestamp.from(now), Timestamp.from(now), tenantId, id);
    }

    public int markFailed(String tenantId, String id) {
        return jdbcTemplate.update("""
                update agent_artifact set status = 'FAILED', updated_at = ?
                where tenant_id = ? and id = ? and status = 'WRITING'
                """, Timestamp.from(Instant.now()), tenantId, id);
    }

    public int delete(String tenantId, String id) {
        return jdbcTemplate.update("delete from agent_artifact where tenant_id = ? and id = ?", tenantId, id);
    }

    public List<ArtifactRecord> findPublishedBefore(Instant cutoff) {
        return jdbcTemplate.query("""
                select * from agent_artifact where status = 'PUBLISHED' and published_at < ?
                """, this::mapRow, Timestamp.from(cutoff));
    }

    public List<ArtifactRecord> findWritingBefore(Instant cutoff) {
        return jdbcTemplate.query("""
                select * from agent_artifact where status = 'WRITING' and created_at < ?
                """, this::mapRow, Timestamp.from(cutoff));
    }

    private ArtifactRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new ArtifactRecord(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("agent_instance_id"), rs.getString("name"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sm3_hex"), rs.getString("status"),
                rs.getString("storage_path"), createdAt.toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }
}

package com.superskidder.kylinops.backend.agentruntime.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemoryStore {

    private final JdbcTemplate jdbcTemplate;

    public MemoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertExecutionRecord(ExecutionRecord record) {
        jdbcTemplate.update("""
                insert into execution_record (
                    id, tenant_id, run_id, agent_instance_id, tool_id, outcome_summary, success, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id(), record.tenantId(), record.runId(), record.agentInstanceId(),
                record.toolId(), record.outcomeSummary(), record.success(),
                Timestamp.from(record.createdAt()));
    }

    /** 最近在前；服务层据此统计同工具连续成功次数。 */
    public List<ExecutionRecord> latestExecutionRecords(String tenantId, String toolId, int limit) {
        return jdbcTemplate.query("""
                select * from execution_record
                where tenant_id = ? and tool_id = ?
                order by created_at desc, id desc
                limit ?
                """, this::mapExecutionRecord, tenantId, toolId, limit);
    }

    public List<ExecutionRecord> executionRecordsByRun(String tenantId, String runId) {
        return jdbcTemplate.query("""
                select * from execution_record
                where tenant_id = ? and run_id = ?
                order by created_at asc, id asc
                """, this::mapExecutionRecord, tenantId, runId);
    }

    public void insertCandidate(MemoryCandidate candidate) {
        jdbcTemplate.update("""
                insert into memory_candidate (
                    id, tenant_id, source_record_id, statement, evidence_json, status, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, candidate.id(), candidate.tenantId(), candidate.sourceRecordId(),
                candidate.statement(), candidate.evidenceJson(), candidate.status(),
                Timestamp.from(candidate.createdAt()));
    }

    public Optional<MemoryCandidate> findCandidate(String tenantId, String id) {
        return jdbcTemplate.query("""
                select * from memory_candidate where tenant_id = ? and id = ?
                """, this::mapCandidate, tenantId, id).stream().findFirst();
    }

    public List<MemoryCandidate> listCandidates(String tenantId, String status) {
        return jdbcTemplate.query("""
                select * from memory_candidate
                where tenant_id = ? and status = ?
                order by created_at asc, id asc
                """, this::mapCandidate, tenantId, status);
    }

    public boolean candidateStatementExists(String tenantId, String statement) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from memory_candidate where tenant_id = ? and statement = ?
                """, Integer.class, tenantId, statement);
        return count != null && count > 0;
    }

    /** 仅允许 fromStatus → toStatus 的守卫更新；命中 0 行即状态已变或不存在。 */
    public int transitionCandidate(String tenantId, String id, String fromStatus, String toStatus) {
        return jdbcTemplate.update("""
                update memory_candidate set status = ?
                where tenant_id = ? and id = ? and status = ?
                """, toStatus, tenantId, id, fromStatus);
    }

    public void insertVerifiedFact(VerifiedFact fact) {
        jdbcTemplate.update("""
                insert into verified_fact (
                    id, tenant_id, statement, verified_by, verification_digest, created_at
                ) values (?, ?, ?, ?, ?, ?)
                """, fact.id(), fact.tenantId(), fact.statement(), fact.verifiedBy(),
                fact.verificationDigest(), Timestamp.from(fact.createdAt()));
    }

    public List<VerifiedFact> listVerifiedFacts(String tenantId, int limit) {
        return jdbcTemplate.query("""
                select * from verified_fact
                where tenant_id = ?
                order by created_at desc, id desc
                limit ?
                """, this::mapFact, tenantId, limit);
    }

    public long countVerifiedFacts(String tenantId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from verified_fact where tenant_id = ?", Long.class, tenantId);
        return count == null ? 0 : count;
    }

    private ExecutionRecord mapExecutionRecord(ResultSet rs, int rowNum) throws SQLException {
        return new ExecutionRecord(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("run_id"), rs.getString("agent_instance_id"), rs.getString("tool_id"),
                rs.getString("outcome_summary"), rs.getBoolean("success"),
                rs.getTimestamp("created_at").toInstant());
    }

    private MemoryCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new MemoryCandidate(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("source_record_id"), rs.getString("statement"),
                rs.getString("evidence_json"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private VerifiedFact mapFact(ResultSet rs, int rowNum) throws SQLException {
        return new VerifiedFact(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("statement"), rs.getString("verified_by"),
                rs.getString("verification_digest"), rs.getTimestamp("created_at").toInstant());
    }
}

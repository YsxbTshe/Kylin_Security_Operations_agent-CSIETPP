package com.superskidder.kylinops.backend.agentruntime.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Run 轮次检查点（Phase 6.2）：每轮模型调用前写一行（run_id PK，覆盖更新）。
 * 重启/唤醒恢复时提供 round_no 用于轮次预算计数恢复（已用轮次不重复计入），
 * 消息历史重建仍由 context_frame MESSAGE 行负责；run 终态时删除检查点。
 */
@Component
public class RunCheckpointService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RunCheckpointService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public record RunCheckpoint(
            String runId, String agentInstanceId, int roundNo,
            String lastToolInvocationId, String checkpointJson, Instant updatedAt
    ) {}

    /** 覆盖式写入当前轮次检查点（先更新，未命中再插入；终态后行已被删除）。 */
    public void save(LeasedAgentRun run, int roundNo, String lastToolInvocationId) {
        Instant now = Instant.now();
        String checkpointJson = writeJson(Map.of(
                "roundNo", roundNo,
                "lastToolInvocationId", lastToolInvocationId == null ? "" : lastToolInvocationId,
                "savedAt", now.toString()));
        int updated = jdbcTemplate.update("""
                update agent_run_checkpoint set agent_instance_id = ?, round_no = ?,
                    last_tool_invocation_id = ?, checkpoint_json = ?, updated_at = ?
                where run_id = ?
                """, run.rootAgentInstanceId(), roundNo, lastToolInvocationId, checkpointJson,
                Timestamp.from(now), run.id());
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into agent_run_checkpoint (
                        run_id, tenant_id, agent_instance_id, round_no,
                        last_tool_invocation_id, checkpoint_json, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?)
                    """, run.id(), run.tenantId(), run.rootAgentInstanceId(), roundNo,
                    lastToolInvocationId, checkpointJson, Timestamp.from(now));
        }
    }

    public Optional<RunCheckpoint> load(String runId) {
        List<RunCheckpoint> rows = jdbcTemplate.query("""
                select run_id, agent_instance_id, round_no, last_tool_invocation_id,
                       checkpoint_json, updated_at
                from agent_run_checkpoint where run_id = ?
                """, (rs, rowNum) -> new RunCheckpoint(rs.getString("run_id"),
                rs.getString("agent_instance_id"), rs.getInt("round_no"),
                rs.getString("last_tool_invocation_id"), rs.getString("checkpoint_json"),
                rs.getTimestamp("updated_at").toInstant()), runId);
        return rows.stream().findFirst();
    }

    /** run 到达终态时清理检查点；返回删除行数。 */
    public int delete(String runId) {
        return jdbcTemplate.update("delete from agent_run_checkpoint where run_id = ?", runId);
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(value));
        } catch (Exception exception) {
            throw new IllegalArgumentException("checkpoint cannot be serialized as JSON", exception);
        }
    }
}

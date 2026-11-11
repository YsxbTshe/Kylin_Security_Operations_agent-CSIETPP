package com.superskidder.kylinops.backend.agentruntime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventRepository;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.security.AccessControlService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Runtime 只读查询端点（Phase 6.5 时间线 UI 配套）。
 *
 * 现有 {@link AgentRunController} 只提供单个 run 的详情与 SSE 事件流，缺少列表、
 * 工具调用、SubAgent handoff 等只读查询；{@link ApprovalController} 只列待审。
 * 本控制器只做**只读**补全，不复用也不改动既有控制器逻辑：
 *   GET /runs                     当前组织的 agent_run 列表（可按状态过滤）
 *   GET /runs/{runId}/timeline    单 run 的时间线聚合（事件 + 工具调用 + handoff + 子 run）
 *   GET /approvals                审批列表（待审与已决统一查询，可按 decision 过滤）
 */
@RestController
@RequestMapping("/api/v2/agent-runtime")
public class AgentRuntimeQueryController {

    private static final Set<String> RUN_STATUSES =
            Set.of("QUEUED", "RUNNING", "WAITING", "COMPLETED", "FAILED", "CANCELLED");
    private static final Set<String> APPROVAL_DECISIONS =
            Set.of("PENDING", "APPROVED", "REJECTED", "REVOKED", "EXPIRED");

    private final JdbcTemplate jdbcTemplate;
    private final AccessControlService accessControl;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeStore runtimeStore;

    public AgentRuntimeQueryController(JdbcTemplate jdbcTemplate, AccessControlService accessControl,
                                       ObjectMapper objectMapper, AgentRuntimeStore runtimeStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessControl = accessControl;
        this.objectMapper = objectMapper;
        this.runtimeStore = runtimeStore;
    }

    @GetMapping("/runs")
    public ApiResponse<List<RunListItem>> listRuns(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        accessControl.requirePermission("agent:read");
        String tenantId = accessControl.currentOrganizationId();
        StringBuilder sql = new StringBuilder("""
                select id, parent_run_id, objective, execution_mode, status, waiting_reason, outcome,
                       budget_json, usage_json, created_at, started_at, completed_at, target_scope_json
                from agent_run where tenant_id = ?
                """);
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase();
            if (!RUN_STATUSES.contains(normalized)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "非法的 run 状态: " + status);
            }
            sql.append(" and status = ?");
            args.add(normalized);
        }
        sql.append(" order by created_at desc limit ?");
        args.add(clampLimit(limit));
        List<RunListItem> rows = jdbcTemplate.query(sql.toString(), (rs, n) -> {
            Map<String, Object> scope = readMap(rs.getString("target_scope_json"));
            return new RunListItem(
                rs.getString("id"), rs.getString("parent_run_id"), rs.getString("objective"),
                rs.getString("execution_mode"), rs.getString("status"), rs.getString("waiting_reason"),
                rs.getString("outcome"), readMap(rs.getString("budget_json")), readMap(rs.getString("usage_json")),
                text(rs.getTimestamp("created_at")), text(rs.getTimestamp("started_at")),
                text(rs.getTimestamp("completed_at")),
                scope.get("nodeId") == null ? null : String.valueOf(scope.get("nodeId")),
                scope.get("service") == null ? null : String.valueOf(scope.get("service")));
        }, args.toArray());
        return ApiResponse.success(rows);
    }

    @GetMapping("/runs/{runId}/timeline")
    public ApiResponse<RunTimeline> runTimeline(
            @PathVariable String runId,
            @RequestParam(required = false, defaultValue = "500") int eventLimit) {
        accessControl.requirePermission("agent:read");
        String tenantId = accessControl.currentOrganizationId();
        RunListItem run = findRun(tenantId, runId);

        List<RunEventItem> events = jdbcTemplate.query("""
                select event_id, sequence_no, event_type, actor_type, actor_id, payload_json, event_hash, created_at
                from agent_run_event where tenant_id = ? and run_id = ? and sequence_no > 0
                order by sequence_no asc limit ?
                """, (rs, n) -> new RunEventItem(rs.getString("event_id"), rs.getLong("sequence_no"),
                rs.getString("event_type"), rs.getString("actor_type"), rs.getString("actor_id"),
                rs.getString("payload_json"), rs.getString("event_hash"), text(rs.getTimestamp("created_at"))),
                tenantId, runId, clampEventLimit(eventLimit));

        List<ToolInvocationItem> toolInvocations = jdbcTemplate.query("""
                select id, tool_id, tool_version, target_node_id, risk_code, read_only, status, error_code,
                       queued_at, started_at, completed_at
                from tool_invocation where tenant_id = ? and run_id = ?
                order by queued_at asc
                """, (rs, n) -> {
            Instant startedAt = instant(rs.getTimestamp("started_at"));
            Instant completedAt = instant(rs.getTimestamp("completed_at"));
            Long durationMs = startedAt != null && completedAt != null
                    ? Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli()) : null;
            return new ToolInvocationItem(rs.getString("id"), rs.getString("tool_id"),
                    rs.getString("tool_version"), rs.getString("target_node_id"), rs.getString("risk_code"),
                    rs.getBoolean("read_only"), rs.getString("status"), rs.getString("error_code"),
                    text(rs.getTimestamp("queued_at")), text(startedAt), text(completedAt), durationMs);
        }, tenantId, runId);

        List<HandoffItem> handoffs = jdbcTemplate.query("""
                select id, objective, status, child_agent_instance_id, created_at, completed_at
                from handoff where tenant_id = ? and run_id = ?
                order by created_at asc
                """, (rs, n) -> new HandoffItem(rs.getString("id"), rs.getString("objective"),
                rs.getString("status"), rs.getString("child_agent_instance_id"),
                text(rs.getTimestamp("created_at")), text(rs.getTimestamp("completed_at"))),
                tenantId, runId);

        List<ChildRunItem> childRuns = jdbcTemplate.query("""
                select id, objective, status, outcome, created_at, completed_at
                from agent_run where tenant_id = ? and parent_run_id = ?
                order by created_at asc
                """, (rs, n) -> new ChildRunItem(rs.getString("id"), rs.getString("objective"),
                rs.getString("status"), rs.getString("outcome"), text(rs.getTimestamp("created_at")),
                text(rs.getTimestamp("completed_at"))), tenantId, runId);

        return ApiResponse.success(new RunTimeline(run, events, toolInvocations, handoffs, childRuns));
    }

    @GetMapping("/runs/{runId}/chain")
    public ApiResponse<ChainView> runChain(@PathVariable String runId) {
        accessControl.requirePermission("agent:read");
        String tenantId = accessControl.currentOrganizationId();
        findRun(tenantId, runId);
        AgentRunEventRepository.ChainVerification verification = runtimeStore.verifyChain(tenantId, runId);
        return ApiResponse.success(new ChainView(
                verification.valid(), verification.eventCount(), verification.brokenEventId(),
                verification.reason(),
                verification.valid() ? "哈希链校验通过" : "哈希链断裂"));
    }

    @GetMapping("/approvals")
    public ApiResponse<List<ApprovalListItem>> listApprovals(
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        accessControl.requirePermission("agent:read");
        String tenantId = accessControl.currentOrganizationId();
        StringBuilder sql = new StringBuilder("""
                select id, tool_invocation_id, run_id, node_id, tool_id, tool_version, arguments_json,
                       risk_ceiling, execution_mode, decision, requested_from_user_id, decided_by_user_id,
                       created_at, decided_at, expires_at, batch_id
                from approval where tenant_id = ?
                """);
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (decision != null && !decision.isBlank()) {
            String normalized = decision.trim().toUpperCase();
            if (!APPROVAL_DECISIONS.contains(normalized)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "非法的审批决策: " + decision);
            }
            sql.append(" and decision = ?");
            args.add(normalized);
        }
        if (runId != null && !runId.isBlank()) {
            sql.append(" and run_id = ?");
            args.add(runId.trim());
        }
        sql.append(" order by created_at desc limit ?");
        args.add(clampLimit(limit));
        List<ApprovalListItem> rows = jdbcTemplate.query(sql.toString(), (rs, n) -> new ApprovalListItem(
                rs.getString("id"), rs.getString("tool_invocation_id"), rs.getString("run_id"),
                rs.getString("node_id"), rs.getString("tool_id"), rs.getString("tool_version"),
                rs.getString("arguments_json"), rs.getString("risk_ceiling"), rs.getString("execution_mode"),
                rs.getString("decision"), rs.getString("requested_from_user_id"),
                rs.getString("decided_by_user_id"), text(rs.getTimestamp("created_at")),
                text(rs.getTimestamp("decided_at")), text(rs.getTimestamp("expires_at")),
                rs.getString("batch_id")), args.toArray());
        return ApiResponse.success(rows);
    }

    private RunListItem findRun(String tenantId, String runId) {
        List<RunListItem> rows = jdbcTemplate.query("""
                select id, parent_run_id, objective, execution_mode, status, waiting_reason, outcome,
                       budget_json, usage_json, created_at, started_at, completed_at, target_scope_json
                from agent_run where tenant_id = ? and id = ?
                """, (rs, n) -> {
                    Map<String, Object> scope = readMap(rs.getString("target_scope_json"));
                    return new RunListItem(rs.getString("id"), rs.getString("parent_run_id"),
                        rs.getString("objective"), rs.getString("execution_mode"), rs.getString("status"),
                        rs.getString("waiting_reason"), rs.getString("outcome"),
                        readMap(rs.getString("budget_json")), readMap(rs.getString("usage_json")),
                        text(rs.getTimestamp("created_at")), text(rs.getTimestamp("started_at")),
                        text(rs.getTimestamp("completed_at")),
                        scope.get("nodeId") == null ? null : String.valueOf(scope.get("nodeId")),
                        scope.get("service") == null ? null : String.valueOf(scope.get("service")));
                }, tenantId, runId);
        return rows.stream().findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "AgentRun 不存在"));
    }

    private int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private int clampEventLimit(int limit) {
        return Math.max(1, Math.min(limit, 1_000));
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String text(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record RunListItem(
            String id, String parentRunId, String objective, String executionMode,
            String status, String waitingReason, String outcome,
            Map<String, Object> budget, Map<String, Object> usage,
            String createdAt, String startedAt, String completedAt,
            String nodeId, String service
    ) {}

    public record RunEventItem(
            String eventId, long sequence, String eventType, String actorType, String actorId,
            String payloadJson, String eventHash, String createdAt
    ) {}

    public record ToolInvocationItem(
            String id, String toolId, String toolVersion, String targetNodeId,
            String riskCode, boolean readOnly, String status, String errorCode,
            String queuedAt, String startedAt, String completedAt, Long durationMs
    ) {}

    public record HandoffItem(
            String id, String objective, String status, String childAgentInstanceId,
            String createdAt, String completedAt
    ) {}

    public record ChildRunItem(
            String id, String objective, String status, String outcome,
            String createdAt, String completedAt
    ) {}

    public record RunTimeline(
            RunListItem run, List<RunEventItem> events, List<ToolInvocationItem> toolInvocations,
            List<HandoffItem> handoffs, List<ChildRunItem> childRuns
    ) {}

    public record ApprovalListItem(
            String id, String toolInvocationId, String runId, String nodeId, String toolId,
            String toolVersion, String argumentsJson, String riskCeiling, String executionMode,
            String decision, String requestedFromUserId, String decidedByUserId,
            String createdAt, String decidedAt, String expiresAt, String batchId
    ) {}

    public record ChainView(
            boolean valid, long eventCount, String brokenEventId, String reason, String message
    ) {}
}

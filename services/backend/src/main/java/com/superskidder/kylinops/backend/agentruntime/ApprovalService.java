package com.superskidder.kylinops.backend.agentruntime;

import com.superskidder.kylinops.backend.abac.AbacRequest;
import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.common.AppException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /**
     * 批准后允许 worker 完成下发的窗口。SM2 令牌不在决策时签发——
     * 令牌必须绑定真实的 execution_attempt_id（Go 侧 validateClaim/CoversInvocation
     * 均要求非空且逐字段相等），而 attempt 只在 dispatch 时产生。
     * 决策只授予「批准窗口」；令牌由 NodeMcpGateway 在 dispatch 前铸造并立即使用。
     */
    static final Duration GRANT_VALIDITY = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final AgentRuntimeStore runtimeStore;
    private final ResourceAccessService resourceAccessService;

    public ApprovalService(JdbcTemplate jdbcTemplate, AgentRuntimeStore runtimeStore,
                           ResourceAccessService resourceAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeStore = runtimeStore;
        this.resourceAccessService = resourceAccessService;
    }

    public List<ApprovalView> listPending(String tenantId, String runId, String toolId, String nodeId) {
        StringBuilder sql = new StringBuilder("""
                select a.id, a.tool_invocation_id, a.tenant_id, a.run_id, a.node_id, a.tool_id,
                       a.tool_version, a.arguments_json, a.risk_ceiling, a.execution_mode,
                       a.decision, a.requested_from_user_id, a.decided_by_user_id,
                       a.agent_instance_id, a.agent_invocation_id, a.created_at, a.decided_at,
                       a.expires_at, a.batch_id
                from approval a where a.tenant_id = ? and a.decision = 'PENDING'
                """);
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (runId != null && !runId.isBlank()) {
            sql.append(" and a.run_id = ?");
            args.add(runId);
        }
        if (toolId != null && !toolId.isBlank()) {
            sql.append(" and a.tool_id = ?");
            args.add(toolId);
        }
        if (nodeId != null && !nodeId.isBlank()) {
            sql.append(" and a.node_id = ?");
            args.add(nodeId);
        }
        sql.append(" order by a.created_at asc");
        return jdbcTemplate.query(sql.toString(), (rs, n) -> new ApprovalView(
                rs.getString("id"), rs.getString("tool_invocation_id"), rs.getString("tenant_id"),
                rs.getString("run_id"), rs.getString("node_id"), rs.getString("tool_id"),
                rs.getString("tool_version"), rs.getString("arguments_json"),
                rs.getString("risk_ceiling"), rs.getString("execution_mode"),
                rs.getString("decision"), rs.getString("requested_from_user_id"),
                rs.getString("decided_by_user_id"), rs.getString("agent_instance_id"),
                rs.getString("agent_invocation_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("decided_at") != null ? rs.getTimestamp("decided_at").toInstant() : null,
                rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
                rs.getString("batch_id")
        ), args.toArray());
    }

    public Optional<ApprovalView> findById(String tenantId, String approvalId) {
        List<ApprovalView> rows = jdbcTemplate.query("""
                select a.id, a.tool_invocation_id, a.tenant_id, a.run_id, a.node_id, a.tool_id,
                       a.tool_version, a.arguments_json, a.risk_ceiling, a.execution_mode,
                       a.decision, a.requested_from_user_id, a.decided_by_user_id,
                       a.agent_instance_id, a.agent_invocation_id, a.created_at, a.decided_at,
                       a.expires_at, a.batch_id
                from approval a where a.tenant_id = ? and a.id = ?
                """, (rs, n) -> new ApprovalView(
                rs.getString("id"), rs.getString("tool_invocation_id"), rs.getString("tenant_id"),
                rs.getString("run_id"), rs.getString("node_id"), rs.getString("tool_id"),
                rs.getString("tool_version"), rs.getString("arguments_json"),
                rs.getString("risk_ceiling"), rs.getString("execution_mode"),
                rs.getString("decision"), rs.getString("requested_from_user_id"),
                rs.getString("decided_by_user_id"), rs.getString("agent_instance_id"),
                rs.getString("agent_invocation_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("decided_at") != null ? rs.getTimestamp("decided_at").toInstant() : null,
                rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
                rs.getString("batch_id")
        ), tenantId, approvalId);
        return rows.stream().findFirst();
    }

    @Transactional
    public ApprovalView decide(String tenantId, String approvalId, String approverId,
                                List<String> approverRoles, boolean approve, String reason) {
        ApprovalView approval = findById(tenantId, approvalId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Approval not found: " + approvalId));
        if (!"PENDING".equals(approval.decision())) {
            throw new AppException(HttpStatus.CONFLICT, "Approval is not pending: " + approval.decision());
        }
        if (approverId.equals(approval.requestedFromUserId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Cannot approve your own request");
        }
        resourceAccessService.requireAccess(tenantId,
                new AbacRequest(approverId, approverRoles, "agent-approval", approval.toolId(), "decide",
                        Map.of("risk", approval.riskCeiling(), "nodeId", approval.nodeId())));

        Instant now = Instant.now();
        String decision = approve ? "APPROVED" : "REJECTED";
        if (approve) {
            // 批准 = 授予一个有界的执行窗口；worker 在窗口内 dispatch 时才铸造绑定
            // 真实 attempt 的 SM2 令牌（ADR-002：令牌绑定精确调用，一次性消费）。
            jdbcTemplate.update("""
                    update approval set decision = 'APPROVED', decided_by_user_id = ?, decided_at = ?,
                        expires_at = ?
                    where id = ? and tenant_id = ? and decision = 'PENDING'
                    """, approverId, java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(now.plus(GRANT_VALIDITY)), approvalId, tenantId);
        } else {
            jdbcTemplate.update("""
                    update approval set decision = 'REJECTED', decided_by_user_id = ?, decided_at = ?
                    where id = ? and tenant_id = ? and decision = 'PENDING'
                    """, approverId, java.sql.Timestamp.from(now), approvalId, tenantId);
        }
        runtimeStore.appendEvent(approval.runId(), tenantId, "APPROVAL_DECIDED", "HUMAN", approverId,
                Map.of("approvalId", approvalId, "decision", decision,
                        "reason", reason != null ? reason : "", "toolId", approval.toolId()));
        // 批准与拒绝都要唤醒：worker 恢复后要么带令牌执行，要么把拒绝结果回填给模型。
        runtimeStore.wakeupRun(approval.runId());
        return findById(tenantId, approvalId).orElseThrow();
    }

    @Transactional
    public List<ApprovalView> batchDecide(String tenantId, List<String> approvalIds, String approverId,
                                           List<String> approverRoles, boolean approve, String reason) {
        List<ApprovalView> results = new ArrayList<>();
        for (String approvalId : approvalIds) {
            try {
                results.add(decide(tenantId, approvalId, approverId, approverRoles, approve, reason));
            } catch (AppException exception) {
                log.warn("batch approval {} failed: {}", approvalId, exception.getMessage());
            }
        }
        return results;
    }

    public String createApproval(String tenantId, String runId, String toolInvocationId,
                                  String nodeId, String toolId, String toolVersion,
                                  String argumentsJson, String riskCeiling, String executionMode,
                                  String agentInstanceId, String agentInvocationId,
                                  String requestedFromUserId) {
        Instant now = Instant.now();
        String id = "appr-" + UUID.randomUUID().toString().replace("-", "");
        String argsDigest = com.superskidder.kylinops.backend.crypto.Sm3.hex(argumentsJson);
        jdbcTemplate.update("""
                insert into approval (
                    id, tool_invocation_id, tenant_id, run_id, node_id, tool_id, tool_version,
                    arguments_json, arguments_digest, risk_ceiling, execution_mode,
                    agent_instance_id, agent_invocation_id, requested_from_user_id,
                    decision, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, id, toolInvocationId, tenantId, runId, nodeId, toolId, toolVersion,
                argumentsJson, argsDigest, riskCeiling, executionMode,
                agentInstanceId, agentInvocationId, requestedFromUserId,
                java.sql.Timestamp.from(now));
        runtimeStore.appendEvent(runId, tenantId, "APPROVAL_CREATED", "RUNTIME", "system",
                Map.of("approvalId", id, "toolId", toolId, "nodeId", nodeId, "riskCeiling", riskCeiling));
        return id;
    }

    /**
     * AUTO 模式的自动批准（ADR-001 §2.10.2）：策略在风险上限内放行写操作时，
     * 审批单直接以 APPROVED 落库——自动批准也是一次有审计载体的批准，而不是「跳过审批」。
     * decided_by_user_id 为空标识决策者是策略而非人；令牌仍由 dispatch 路径铸造并一次性消费，
     * 端侧特权助手的验签边界对自动批准与人工批准完全一致。
     */
    @Transactional
    public ApprovalGrant createAutoApproval(String tenantId, String runId, String toolInvocationId,
                                            String nodeId, String toolId, String toolVersion,
                                            String argumentsJson, String riskCeiling,
                                            String agentInstanceId, String agentInvocationId,
                                            String requestedFromUserId, String policyVersion) {
        Instant now = Instant.now();
        String id = "appr-" + UUID.randomUUID().toString().replace("-", "");
        String argsDigest = com.superskidder.kylinops.backend.crypto.Sm3.hex(argumentsJson);
        Instant expiresAt = now.plus(GRANT_VALIDITY);
        jdbcTemplate.update("""
                insert into approval (
                    id, tool_invocation_id, tenant_id, run_id, node_id, tool_id, tool_version,
                    arguments_json, arguments_digest, risk_ceiling, execution_mode,
                    agent_instance_id, agent_invocation_id, requested_from_user_id,
                    decision, decided_at, expires_at, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTO', ?, ?, ?, 'APPROVED', ?, ?, ?)
                """, id, toolInvocationId, tenantId, runId, nodeId, toolId, toolVersion,
                argumentsJson, argsDigest, riskCeiling,
                agentInstanceId, agentInvocationId, requestedFromUserId,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(expiresAt),
                java.sql.Timestamp.from(now));
        runtimeStore.appendEvent(runId, tenantId, "APPROVAL_AUTO_GRANTED", "RUNTIME", "policy",
                Map.of("approvalId", id, "toolId", toolId, "nodeId", nodeId,
                        "riskCeiling", riskCeiling, "policyVersion",
                        policyVersion != null ? policyVersion : ""));
        return new ApprovalGrant(id, toolInvocationId, agentInvocationId, "APPROVED",
                riskCeiling, "AUTO", argsDigest, expiresAt, false);
    }

    /**
     * 同一绑定（run + node + tool + 参数摘要）的最新审批。绑定即「批准的是什么就执行什么」：
     * 参数摘要变化视为另一次请求，需要新的审批。consumed 由 execution_attempt 的存在性推导
     * ——批准的占位 tool_invocation 一旦产生 attempt 即视为已消费（一次性）。
     */
    public Optional<ApprovalGrant> findLatestByBinding(String tenantId, String runId, String nodeId,
                                                       String toolId, String argumentsDigest) {
        List<ApprovalGrant> rows = jdbcTemplate.query("""
                select a.id, a.tool_invocation_id, a.agent_invocation_id, a.decision,
                       a.risk_ceiling, a.execution_mode, a.arguments_digest, a.expires_at,
                       exists(select 1 from execution_attempt ea
                              where ea.tool_invocation_id = a.tool_invocation_id) as consumed
                from approval a
                where a.tenant_id = ? and a.run_id = ? and a.node_id = ? and a.tool_id = ?
                  and a.arguments_digest = ?
                order by a.created_at desc, a.id desc limit 1
                """, (rs, n) -> new ApprovalGrant(
                rs.getString("id"), rs.getString("tool_invocation_id"),
                rs.getString("agent_invocation_id"), rs.getString("decision"),
                rs.getString("risk_ceiling"), rs.getString("execution_mode"),
                rs.getString("arguments_digest"),
                rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
                rs.getBoolean("consumed")
        ), tenantId, runId, nodeId, toolId, argumentsDigest);
        return rows.stream().findFirst();
    }

    /** 已批准但超出批准窗口未执行：置 EXPIRED（终态），需要重新走审批。 */
    public void markExpired(String tenantId, String runId, String approvalId) {
        int updated = jdbcTemplate.update("""
                update approval set decision = 'EXPIRED'
                where id = ? and tenant_id = ? and decision = 'APPROVED'
                """, approvalId, tenantId);
        if (updated > 0) {
            runtimeStore.appendEvent(runId, tenantId, "APPROVAL_EXPIRED", "RUNTIME", "system",
                    Map.of("approvalId", approvalId));
        }
    }

    /** 绑定视图：worker 依据 decision/expiresAt/consumed 决定执行、等待、拒绝回填或重新建单。 */
    public record ApprovalGrant(
            String id, String toolInvocationId, String agentInvocationId, String decision,
            String riskCeiling, String executionMode, String argumentsDigest,
            Instant expiresAt, boolean consumed
    ) {
        public boolean pending() { return "PENDING".equals(decision); }
        /** REVOKED 与 REJECTED 同义处理：人已否决，同一绑定不得自动重新建单。 */
        public boolean rejected() { return "REJECTED".equals(decision) || "REVOKED".equals(decision); }
        public boolean actionable(Instant now) {
            return "APPROVED".equals(decision) && !consumed
                    && expiresAt != null && now.isBefore(expiresAt);
        }
        public boolean approvedButExpired(Instant now) {
            return "APPROVED".equals(decision) && !consumed
                    && (expiresAt == null || !now.isBefore(expiresAt));
        }
    }

    public record ApprovalView(
            String id, String toolInvocationId, String tenantId, String runId,
            String nodeId, String toolId, String toolVersion, String argumentsJson,
            String riskCeiling, String executionMode, String decision,
            String requestedFromUserId, String decidedByUserId,
            String agentInstanceId, String agentInvocationId,
            Instant createdAt, Instant decidedAt, Instant expiresAt, String batchId
    ) {}
}

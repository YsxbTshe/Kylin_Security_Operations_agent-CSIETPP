package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.agentruntime.ApprovalService;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.common.AppException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 7 迭代 7.1 验收基线 18.2（安全）——审批仓库切片；迭代 7.5 改造后语义：
 *
 * 决策不再签发令牌（令牌必须绑定 dispatch 时才存在的 execution_attempt，改由
 * NodeMcpGateway 在下发前铸造）。决策只做两件事：授予有界批准窗口（expires_at）
 * 并唤醒 run。本类验收：
 *   - 被拒绝（含撤销语义）的审批永不产生执行授权（无 attempt、可唤醒 run 回填拒绝结果）；
 *   - 批准回填 5 分钟批准窗口并唤醒 WAITING/APPROVAL 的 run；
 *   - 发起人不可自批；
 *   - 绑定查询 + attempt 认领的一次性消费语义。
 */
class ApprovalRepositoryAcceptanceTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.restart"), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private ApprovalService approvalService;
    private String runId;
    private String instanceId;
    private String toolInvocationId;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        ResourceAccessService resourceAccessService = mock(ResourceAccessService.class);
        approvalService = new ApprovalService(jdbc, store, resourceAccessService);

        // 第二名用户：审批人不得与发起人相同（ApprovalService.decide 强约束）。
        jdbc.update("""
                insert into app_user_account (id, username, display_name, password, enabled)
                values ('user-beta', 'beta', 'Beta Approver', 'sm3-hash', true)
                """);
        seedToolPackage();

        runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", GRANT, BUDGET, ZERO_USAGE);
        // V55 状态机：审批等待 = WAITING + waiting_reason='APPROVAL'（单条语句满足检查约束）。
        jdbc.update("update agent_run set status = 'WAITING', waiting_reason = 'APPROVAL' where id = ?",
                runId);
        instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        toolInvocationId = insertAwaitingToolInvocation();
    }

    private void seedToolPackage() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into tool_package (id, package_key, title, enabled, created_at, updated_at)
                values ('tp-kylin-service', 'kylin-service', 'Kylin Service Tools', true, ?, ?)
                """, now, now);
        jdbc.update("""
                insert into tool_package_version (
                    id, package_id, version, manifest_json, manifest_digest, schema_digest,
                    implementation_digest, signature_algorithm, signing_key_id, signature_der,
                    status, created_at
                ) values ('tpv-kylin-service-1', 'tp-kylin-service', '1.0.0', '{}', 'digest-manifest',
                    'digest-schema', 'digest-impl', 'SM2', 'key-1', 'sig', 'APPROVED', ?)
                """, now);
    }

    private String insertAwaitingToolInvocation() {
        String id = "tool-awaiting-1";
        jdbc.update("""
                insert into tool_invocation (
                    id, run_id, tenant_id, agent_instance_id, tool_package_version_id, tool_id,
                    tool_version, executor, target_node_id, arguments_json, arguments_digest,
                    input_schema_digest, output_schema_digest, grant_digest, risk_code, read_only,
                    always_require_approval, idempotency_type, max_attempts, status, input_revision,
                    queued_at
                ) values (?, ?, ?, ?, 'tpv-kylin-service-1', 'kylin.service.restart', '1.0.0', 'NODE',
                    'node-1', '{"service":"nginx"}', 'digest-args', 'digest-in', 'digest-out',
                    'digest-grant', 'R3', false, true, 'AT_MOST_ONCE', 1, 'QUEUED', 0, ?)
                """, id, runId, AgentTestDatabase.TENANT_ID, instanceId, Timestamp.from(Instant.now()));
        return id;
    }

    private String createPendingApproval() {
        return approvalService.createApproval(AgentTestDatabase.TENANT_ID, runId, toolInvocationId,
                "node-1", "kylin.service.restart", "1.0.0", "{\"service\":\"nginx\"}", "R3", "CAUTIOUS",
                instanceId, null, AgentTestDatabase.USER_ID);
    }

    /** 18.2：被拒绝的审批不得产生任何执行授权；拒绝后再次尝试决策（复活）必须被拒。 */
    @Test
    void rejectedApprovalNeverGrantsExecutionAndLaterDecisionsAreRejected() {
        String approvalId = createPendingApproval();

        ApprovalService.ApprovalView rejected = approvalService.decide(AgentTestDatabase.TENANT_ID,
                approvalId, "user-beta", List.of("approver"), false, "too risky");

        assertThat(rejected.decision()).isEqualTo("REJECTED");
        assertThat(rejected.decidedAt()).isNotNull();
        // 授权面：拒绝不产生批准窗口，绑定不可执行，占位 invocation 永不产生 attempt。
        assertThat(rejected.expiresAt()).isNull();
        Optional<ApprovalService.ApprovalGrant> grant = approvalService.findLatestByBinding(
                AgentTestDatabase.TENANT_ID, runId, "node-1", "kylin.service.restart",
                com.superskidder.kylinops.backend.crypto.Sm3.hex("{\"service\":\"nginx\"}"));
        assertThat(grant).isPresent();
        assertThat(grant.get().rejected()).isTrue();
        assertThat(grant.get().actionable(Instant.now())).isFalse();
        // 拒绝即终态：任何后续批准尝试（复活已撤销/拒绝的审批）必须 409。
        assertThatThrownBy(() -> approvalService.decide(AgentTestDatabase.TENANT_ID,
                approvalId, "user-beta", List.of("approver"), true, "changed my mind"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(approvalService.findById(AgentTestDatabase.TENANT_ID, approvalId).orElseThrow()
                .decision()).isEqualTo("REJECTED");
        // 迭代 7.5 语义：拒绝同样唤醒 run——worker 需要把拒绝结果回填给模型改道，
        // 而不是让 run 在 WAITING 里挂到超时清扫。
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("QUEUED");
        assertThat(store.eventsAfter(AgentTestDatabase.TENANT_ID, runId, 0, 100).stream()
                .map(event -> event.eventType()))
                .contains("APPROVAL_CREATED", "APPROVAL_DECIDED", "RUN_WAKEUP");
    }

    /** 18.2：批准必须授予有界批准窗口（5 分钟）并唤醒 run；令牌由 dispatch 路径铸造。 */
    @Test
    void approvedApprovalGrantsBoundedWindowAndWakesRun() {
        String approvalId = createPendingApproval();
        Instant before = Instant.now();

        ApprovalService.ApprovalView approved = approvalService.decide(AgentTestDatabase.TENANT_ID,
                approvalId, "user-beta", List.of("approver"), true, "proceed");

        assertThat(approved.decision()).isEqualTo("APPROVED");
        assertThat(approved.expiresAt()).isNotNull();
        assertThat(approved.expiresAt()).isAfterOrEqualTo(before.plusSeconds(295));
        assertThat(approved.expiresAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(305));
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("QUEUED");
        assertThat(store.eventsAfter(AgentTestDatabase.TENANT_ID, runId, 0, 100).stream()
                .map(event -> event.eventType())).contains("RUN_WAKEUP");
        // 绑定视图可执行（未消费、窗口内）。
        ApprovalService.ApprovalGrant grant = approvalService.findLatestByBinding(
                AgentTestDatabase.TENANT_ID, runId, "node-1", "kylin.service.restart",
                com.superskidder.kylinops.backend.crypto.Sm3.hex("{\"service\":\"nginx\"}")).orElseThrow();
        assertThat(grant.actionable(Instant.now())).isTrue();
    }

    /** 18.2：发起人不得自批（权限分离向量）。 */
    @Test
    void requesterCannotApproveOwnApproval() {
        String approvalId = createPendingApproval();

        assertThatThrownBy(() -> approvalService.decide(AgentTestDatabase.TENANT_ID,
                approvalId, AgentTestDatabase.USER_ID, List.of("approver"), true, "self approve"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(approvalService.findById(AgentTestDatabase.TENANT_ID, approvalId).orElseThrow()
                .decision()).isEqualTo("PENDING");
    }

    /** 迭代 7.5：批准的消费是一次性的——attempt 认领只能成功一次，消费后绑定不再可执行。 */
    @Test
    void approvedGrantIsConsumedExactlyOnceByAttemptClaim() {
        String approvalId = createPendingApproval();
        approvalService.decide(AgentTestDatabase.TENANT_ID, approvalId, "user-beta",
                List.of("approver"), true, "proceed");
        String argsDigest = com.superskidder.kylinops.backend.crypto.Sm3.hex("{\"service\":\"nginx\"}");

        Optional<String> first = store.claimAttemptForApprovedInvocation(
                toolInvocationId, AgentTestDatabase.TENANT_ID, "node-1", 1L, argsDigest);
        Optional<String> second = store.claimAttemptForApprovedInvocation(
                toolInvocationId, AgentTestDatabase.TENANT_ID, "node-1", 2L, argsDigest);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        ApprovalService.ApprovalGrant grant = approvalService.findLatestByBinding(
                AgentTestDatabase.TENANT_ID, runId, "node-1", "kylin.service.restart",
                argsDigest).orElseThrow();
        assertThat(grant.consumed()).isTrue();
        assertThat(grant.actionable(Instant.now())).isFalse();
    }
}

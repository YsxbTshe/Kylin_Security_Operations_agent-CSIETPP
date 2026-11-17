package com.superskidder.kylinops.backend.agentruntime.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.abac.AbacPolicyEngine;
import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.policy.IntentAlignmentGuard;
import com.superskidder.kylinops.backend.policy.PolicyEngine;
import com.superskidder.kylinops.backend.policy.PolicyToolCatalog;
import com.superskidder.kylinops.backend.registry.AgentRegistryService;
import com.superskidder.kylinops.backend.registry.AgentUpsertRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 专家注册表（Agent IAM）在**执行路径**上的验收。
 *
 * <p>此前 {@code AgentRegistryService.authorize} 只被自己的 Controller 调用，DispatchGate
 * 的十二步里没有任何一步查它——注册表里把某个专家停用、把它的工具删掉、把风险上限调低，
 * 对下一次真实下发没有任何影响。本类用**真实迁移种下的专家**（不是测试自己 mock 的）验收
 * 接线后的判定：同一个人、同一把工具、同一台主机，仅「哪位专家在发起」不同即得出相反结论。
 *
 * <p>另一半同样重要：{@code agentKey} 为空时**必须完全不判定**。根 Agent 与存量 run 没有
 * 注册身份，若把「无身份」当作「拒绝」，整条演示链路会被一次性打断。相关用例逐条钉死。
 */
class AgentRegistryEnvelopeDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT_ID = "org-alpha";
    private static final List<String> SECURITY_OPERATOR = List.of("security_operator");

    /** 种子专家「存储清理专家」：封套含 kylin.disk.usage，风险上限 R3，启用中。 */
    private static final String CLEANUP = "cleanup-operator";
    /** 种子专家「态势采集专家」：只读采集类工具，封套**不含** kylin.disk.usage / service.restart。 */
    private static final String SENSE = "sense-collector";

    private JdbcTemplate jdbc;
    private DispatchGate gate;
    private AgentRegistryService registry;

    @BeforeEach
    void setUp() {
        jdbc = createDatabase();
        PolicyToolCatalog toolCatalog = new PolicyToolCatalog(jdbc, MAPPER);
        registry = new AgentRegistryService(jdbc, MAPPER);
        gate = new DispatchGate(
                new PolicyEngine(MAPPER, toolCatalog, jdbc),
                new IntentAlignmentGuard(toolCatalog),
                new AbacPolicyEngine(MAPPER),
                new ResourceAccessService(jdbc, MAPPER),
                registry);
    }

    // --- 封套判定本身 ---

    /** 封套内的只读工具：注册表不改变结论，照常放行。 */
    @Test
    void toolInsideEnvelopeIsAllowed() {
        DispatchGate.Decision decision = gate.evaluate(diskUsage("prod-web-01", CLEANUP));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.ALLOWED);
        assertThat(decision.rejectionCode()).isNull();
    }

    /** 同一个人、同一把工具、同一台主机，换一位封套不含该工具的专家 → 拒。 */
    @Test
    void toolOutsideEnvelopeIsDenied() {
        DispatchGate.Decision decision = gate.evaluate(diskUsage("prod-web-01", SENSE));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo(DispatchGate.REJECT_AGENT_ENVELOPE_DENIED);
        assertThat(decision.rejectionReason()).contains(SENSE).contains("能力封套");
    }

    /** 风险上限是硬顶：工具在封套内，但动作风险高于该专家声明的上限 → 拒。 */
    @Test
    void riskAboveAgentCeilingIsDenied() {
        registry.upsert(TENANT_ID, new AgentUpsertRequest(
                "restart-operator", "服务重启专家", "executor", "仅允许 R2 及以下",
                List.of("kylin-os"), List.of("kylin.service.restart"),
                "R2", 10_000L, true, "1.0.0"));

        DispatchGate.Decision decision = gate.evaluate(restart("prod-web-01", "restart-operator"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo(DispatchGate.REJECT_AGENT_ENVELOPE_DENIED);
        assertThat(decision.rejectionReason()).contains("R3").contains("上限 R2");
    }

    /** 在注册表里停用一位专家，下一次下发立即被拒——启停开关必须真的管用。 */
    @Test
    void disabledAgentIsDenied() {
        assertThat(gate.evaluate(diskUsage("prod-web-01", CLEANUP)).outcome())
                .isEqualTo(DispatchGate.Decision.Outcome.ALLOWED);

        registry.setEnabled(TENANT_ID, CLEANUP, false);

        DispatchGate.Decision decision = gate.evaluate(diskUsage("prod-web-01", CLEANUP));
        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo(DispatchGate.REJECT_AGENT_ENVELOPE_DENIED);
        assertThat(decision.rejectionReason()).contains("已停用");
    }

    /** 伪造一个未注册的身份不会换来更宽的权限——fail-closed，一律拒。 */
    @Test
    void unregisteredAgentKeyIsDenied() {
        DispatchGate.Decision decision = gate.evaluate(diskUsage("prod-web-01", "ghost-collector"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo(DispatchGate.REJECT_AGENT_ENVELOPE_DENIED);
        assertThat(decision.rejectionReason()).contains("未注册");
    }

    // --- 不误伤：无注册身份的调用行为必须与接入前逐字节一致 ---

    /**
     * 根 Agent 与存量 run 没有 registry key。三种"空"写法（null / 空串 / 全空白）都必须
     * 完全跳过封套判定，否则现有全部链路会在下发第一个工具时被打断。
     */
    @Test
    void absentAgentKeyIsNotEvaluatedAtAll() {
        for (String absent : new String[] {null, "", "   "}) {
            assertThat(gate.evaluate(diskUsage("prod-web-01", absent)).outcome())
                    .as("read-only dispatch with agentKey=[%s]", absent)
                    .isEqualTo(DispatchGate.Decision.Outcome.ALLOWED);

            DispatchGate.Decision write = gate.evaluate(restart("prod-web-01", absent));
            assertThat(write.outcome())
                    .as("write dispatch with agentKey=[%s]", absent)
                    .isEqualTo(DispatchGate.Decision.Outcome.NEEDS_APPROVAL);
            assertThat(write.rejectionCode()).isNull();
        }
    }

    /**
     * 停用**全部**专家也不能波及无身份的调用：注册表是 Agent 的权限面，
     * 不是人的权限面，两者正交。
     */
    @Test
    void disablingEveryExpertDoesNotAffectDispatchWithoutAgentKey() {
        registry.listAgents(TENANT_ID).forEach(agent -> registry.setEnabled(TENANT_ID, agent.agentKey(), false));

        assertThat(gate.evaluate(diskUsage("prod-web-01", null)).outcome())
                .isEqualTo(DispatchGate.Decision.Outcome.ALLOWED);
    }

    // --- 十二步中的位置 ---

    /**
     * 封套判定在**工具维 ABAC 之后**：发起人自己就没有工具权限时，拒绝码必须指向人
     * （{@code ABAC_DENIED}），而不是把责任推给 Agent 封套——否则运维会去改注册表而不是改角色。
     */
    @Test
    void toolDimensionAbacIsEvaluatedBeforeTheEnvelope() {
        DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                TENANT_ID, "run-registry", "user-auditor", List.of("auditor"),
                "查看 prod-web-01 的磁盘占用", "prod-web-01", "kylin.disk.usage", "{}",
                true, false, "R0", "CAUTIOUS", List.of(), false, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2", SENSE));

        assertThat(decision.rejectionCode()).isEqualTo("ABAC_DENIED");
    }

    /**
     * 封套判定在**主机维 ABAC 之前**：越封套的调用哪怕目标主机也被 HOST 策略拒绝，
     * 也要报封套原因——身份问题比目标问题更内层，换台主机重试不会改变结论。
     */
    @Test
    void envelopeIsEvaluatedBeforeHostDimensionAbac() {
        // 参照组：无 agentKey 时，这条请求会被 HOST 维度（prod-db-* 禁 operate）拒绝。
        assertThat(gate.evaluate(restart("prod-db-01", null)).rejectionCode())
                .isEqualTo("ABAC_DENIED_HOST");

        DispatchGate.Decision decision = gate.evaluate(restart("prod-db-01", SENSE));

        assertThat(decision.rejectionCode()).isEqualTo(DispatchGate.REJECT_AGENT_ENVELOPE_DENIED);
    }

    // --- 身份桥（V10 registry_agent_key）的持久化行为 ---

    /** 招募时绑定的专家身份可被下一轮读回——DispatchGate 的 agentKey 就来自这里。 */
    @Test
    void registryAgentKeyRoundTripsThroughAgentInstance() {
        AgentRuntimeStore store = new AgentRuntimeStore(jdbc, MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        String instanceId = insertRunWithInstance();

        assertThat(store.findRegistryAgentKey(instanceId)).isEmpty();
        assertThat(store.bindRegistryAgentKey(instanceId, CLEANUP)).isTrue();
        assertThat(store.findRegistryAgentKey(instanceId)).contains(CLEANUP);
    }

    /** 身份一旦确立不可改写：否则被攻陷的 Agent 改一次自己的 key 就能换一副更宽的封套。 */
    @Test
    void registryAgentKeyCannotBeRebound() {
        AgentRuntimeStore store = new AgentRuntimeStore(jdbc, MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        String instanceId = insertRunWithInstance();

        assertThat(store.bindRegistryAgentKey(instanceId, SENSE)).isTrue();
        assertThat(store.bindRegistryAgentKey(instanceId, CLEANUP)).isFalse();
        assertThat(store.findRegistryAgentKey(instanceId)).contains(SENSE);
    }

    // --- fixtures ---

    /** 只读诊断：security_operator 在工具维与主机维 ABAC 上均已放行，差异只来自封套。 */
    private DispatchGate.DispatchRequest diskUsage(String nodeId, String agentKey) {
        return new DispatchGate.DispatchRequest(
                TENANT_ID, "run-registry", "user-secops", SECURITY_OPERATOR,
                "查看 " + nodeId + " 的磁盘占用", nodeId, "kylin.disk.usage", "{}",
                true, false, "R0", "CAUTIOUS", List.of(), false, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2", agentKey);
    }

    /** R3 写：无 agentKey 时在 prod-web-01 上落到 NEEDS_APPROVAL、在 prod-db-01 上被 HOST 拒。 */
    private DispatchGate.DispatchRequest restart(String nodeId, String agentKey) {
        return new DispatchGate.DispatchRequest(
                TENANT_ID, "run-registry", "user-secops", SECURITY_OPERATOR,
                "确认执行重启 nginx 服务", nodeId, "kylin.service.restart", "{\"service\":\"nginx\"}",
                false, false, "R3", "CAUTIOUS", List.of(), false, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2", agentKey);
    }

    private String insertRunWithInstance() {
        String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String instanceId = "agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into agent_run (
                    id, tenant_id, human_principal_id, objective, execution_mode, target_scope_json,
                    target_scope_digest, grant_snapshot_json, status, budget_json, usage_json,
                    created_at, updated_at
                ) values (?, ?, 'user-secops', 'registry bridge fixture', 'CAUTIOUS', '{}', 'digest',
                    '{}', 'QUEUED', '{}', '{}', ?, ?)
                """, runId, TENANT_ID, now, now);
        jdbc.update("""
                insert into agent_instance (
                    id, run_id, tenant_id, instance_name, avatar_seed, definition_snapshot_json,
                    objective, depth, status, reasoning_level, grant_snapshot_json, grant_digest,
                    budget_json, usage_json, created_at, updated_at
                ) values (?, ?, ?, 'Fixture Agent', 'fixture', '{}', 'registry bridge fixture', 1,
                    'QUEUED', 'OFF', '{}', 'digest', '{}', '{}', ?, ?)
                """, instanceId, runId, TENANT_ID, now, now);
        return instanceId;
    }

    /** 按生产 Flyway 顺序执行全部迁移，使专家种子与 ABAC 策略都与生产一致。 */
    private static JdbcTemplate createDatabase() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:agent-envelope-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            for (String migration : new String[] {
                    "db/migration/V1__baseline.sql",
                    "db/migration/V2__node_session_native_guard.sql",
                    "db/migration/V3__perception_readonly_tools.sql",
                    "db/migration/V4__honest_write_output_schema.sql",
                    "db/migration/V5__agent_tool_abac_policies.sql",
                    "db/migration/V6__agent_approval_abac_policies.sql",
                    "db/migration/V7__user_must_change_password.sql",
                    "db/migration/V8__agent_approval_permission.sql",
                    "db/migration/V9__drop_outbox_event.sql",
                    "db/migration/V10__agent_instance_registry_key.sql",
                    "db/migration/V11__runtime_tool_package_versions.sql",
            }) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("agent registry envelope test schema failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }
}

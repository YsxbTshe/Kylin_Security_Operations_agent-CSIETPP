package com.superskidder.kylinops.backend.agentruntime.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.abac.AbacPolicyEngine;
import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.policy.IntentAlignmentGuard;
import com.superskidder.kylinops.backend.policy.PolicyEngine;
import com.superskidder.kylinops.backend.policy.PolicyToolCatalog;
import com.superskidder.kylinops.backend.registry.AgentRegistryService;
import java.sql.Connection;
import java.sql.SQLException;
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
 * 资源级 ABAC 的**主机维度**在执行路径上的验收（ADR-003 §2.1）。
 *
 * <p>此前 DispatchGate 只做 {@code resource_type='agent-tool'} 判定，{@code nodeId} 仅塞进
 * context，而 {@code AbacPolicyEngine.applies} 从不读 context —— 库里种下的全部 HOST 型策略
 * （{@code rp-secops-prod-db-deny} 等）都是永不生效的死策略，「对 prod-db-* 发起写 → ABAC DENY」
 * 这条现场演示动作在代码里根本不存在。
 *
 * <p>本类用**真实迁移种下的策略**（不是测试自己 mock 的）验收串联后的两维判定：
 * 同一个 {@code security_operator}、同一次 R3 写调用，仅目标主机不同即得出相反结论。
 */
class HostResourceAbacDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT_ID = "org-alpha";
    /** 生产种子把 HOST 策略挂在角色上；本主体只带 security_operator 一个角色。 */
    private static final List<String> SECURITY_OPERATOR = List.of("security_operator");

    private DispatchGate gate;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = createDatabase();
        PolicyToolCatalog toolCatalog = new PolicyToolCatalog(jdbc, MAPPER);
        gate = new DispatchGate(
                new PolicyEngine(MAPPER, toolCatalog, jdbc),
                new IntentAlignmentGuard(toolCatalog),
                new AbacPolicyEngine(MAPPER),
                new ResourceAccessService(jdbc, MAPPER),
                new AgentRegistryService(jdbc, MAPPER));
    }

    /** ADR-003 §2.1 演示动作：对生产数据库主机发起 R3 写 → HOST 维度显式 DENY 压过通配 ALLOW。 */
    @Test
    void writeAgainstProductionDatabaseHostIsDeniedByHostPolicy() {
        DispatchGate.Decision decision = gate.evaluate(restartRequest("prod-db-01"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("ABAC_DENIED_HOST");
        assertThat(decision.rejectionReason()).contains("prod-db-01").contains("operate");
    }

    /** 同一主体、同一工具、同一风险，只是换台非数据库生产机 → 不被 HOST 维度拦下。 */
    @Test
    void sameWriteAgainstOrdinaryProductionHostPassesHostPolicy() {
        DispatchGate.Decision decision = gate.evaluate(restartRequest("prod-web-01"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.NEEDS_APPROVAL);
        assertThat(decision.rejectionCode()).isNull();
    }

    /**
     * 只读调用走 {@code read} 动作口径：{@code prod-db-*} 的 DENY 只针对 operate，
     * 因此对同一台数据库主机的只读诊断仍然放行——高危拦截不能退化成"整台机器不可观测"。
     */
    @Test
    void readOnlyDiagnosisAgainstProductionDatabaseHostIsStillAllowed() {
        DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                TENANT_ID, "run-abac", "user-secops", SECURITY_OPERATOR,
                "查看 prod-db-01 的磁盘占用", "prod-db-01", "kylin.disk.usage", "{}",
                true, false, /* riskFloor */ "R0", "CAUTIOUS", List.of(), false, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.ALLOWED);
    }

    /** 无任何 HOST 策略的主体（auditor 只有读权限之外的空策略集）→ fail-closed 拒绝。 */
    @Test
    void principalWithoutAnyHostPolicyIsDeniedByDefault() {
        DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                TENANT_ID, "run-abac", "user-auditor", List.of("auditor"),
                "查看 prod-web-01 的磁盘占用", "prod-web-01", "kylin.disk.usage", "{}",
                true, false, /* riskFloor */ "R0", "CAUTIOUS", List.of(), false, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        // 工具维度先行拒绝：auditor 连 agent-tool 策略都没有，两维都 fail-closed。
        assertThat(decision.rejectionCode()).startsWith("ABAC_DENIED");
    }

    /** R3 重启（写）：工具维度对 security_operator 已放行，判定差异只来自 HOST 维度。 */
    private DispatchGate.DispatchRequest restartRequest(String nodeId) {
        return new DispatchGate.DispatchRequest(
                TENANT_ID, "run-abac", "user-secops", SECURITY_OPERATOR,
                "确认执行重启 nginx 服务", nodeId, "kylin.service.restart", "{\"service\":\"nginx\"}",
                false, false, /* riskFloor */ "R3", "CAUTIOUS", List.of(), false, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2");
    }

    /** 按生产 Flyway 顺序执行全部迁移，使 ABAC 策略与本用例断言的是同一批种子。 */
    private static JdbcTemplate createDatabase() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:host-abac-" + UUID.randomUUID()
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
            throw new IllegalStateException("host ABAC test schema failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }
}

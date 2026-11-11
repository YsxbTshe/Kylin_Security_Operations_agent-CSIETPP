package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.memory.KnowledgeSearchTool;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillLoadTool;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillSearchTool;
import com.superskidder.kylinops.backend.agentruntime.vfs.VfsReadTool;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RUNTIME 工具必须能走完审批分支，而不是把整条 run 打成 FAILED。
 *
 * <p><b>这条测试在挡什么</b>：{@code tool_invocation.tool_package_version_id} 是
 * not null 且外键到 {@code tool_package_version}。控制面工具各自声明了
 * {@code toolpkgver-runtime-*}，但这些行一度从未被任何迁移种过——于是只要
 * DispatchGate 判出 NEEDS_APPROVAL（敏感路径升 R4、auditd/KYSEC 升级、或工具自身
 * alwaysRequireApproval），{@code createAwaitingToolInvocation} 的 insert 就撞外键，
 * 异常上抛，用户看到的不是「这一步需要审批」而是「任务失败」，失败原因还是一个
 * 数据库外键错误。策略判定退化成崩溃。V11 补上这些行。
 *
 * <p>用不变量而非逐条枚举来断言：新增 RUNTIME 工具时若忘记种包版本行，这里会红。
 */
class RuntimeToolApprovalPathTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 所有 RUNTIME 工具声明的 packageVersionId —— 新增工具请一并加到这里。 */
    private static List<String> runtimePackageVersionIds() {
        return List.of(
                KnowledgeSearchTool.PACKAGE_VERSION_ID,
                RecruitAgentTool.PACKAGE_VERSION_ID,
                SkillSearchTool.PACKAGE_VERSION_ID,
                SkillLoadTool.PACKAGE_VERSION_ID,
                VfsReadTool.PACKAGE_VERSION_ID);
    }

    @Test
    void everyRuntimeToolHasASeededPackageVersionRow() {
        JdbcTemplate jdbc = AgentTestDatabase.create();

        for (String packageVersionId : runtimePackageVersionIds()) {
            Integer count = jdbc.queryForObject(
                    "select count(1) from tool_package_version where id = ?", Integer.class, packageVersionId);
            assertThat(count)
                    .as("RUNTIME 工具 %s 缺少 tool_package_version 行——审批分支会撞外键并把 run 打成 FAILED",
                            packageVersionId)
                    .isEqualTo(1);
        }
    }

    /**
     * 直接把外键这一步跑一遍：插一条指向运行时包版本的 tool_invocation。
     * 上一个断言只证明行存在，这一条证明它真的能被引用（列类型 / 约束都对得上）。
     */
    @Test
    void awaitingInvocationCanReferenceRuntimePackageVersion() {
        JdbcTemplate jdbc = AgentTestDatabase.create();
        String runId = "run-approval-path";
        String instanceId = "inst-approval-path";
        seedRun(jdbc, runId, instanceId);

        for (String packageVersionId : runtimePackageVersionIds()) {
            String invocationId = "inv-" + packageVersionId;
            assertThatCode(() -> jdbc.update("""
                    insert into tool_invocation (id, run_id, tenant_id, agent_instance_id,
                        tool_package_version_id, tool_id, tool_version, executor, arguments_json,
                        arguments_digest, input_schema_digest, output_schema_digest, grant_digest,
                        risk_code, read_only, always_require_approval, idempotency_type, max_attempts,
                        status, input_revision, queued_at)
                    values (?, ?, ?, ?, ?, 'kylin.runtime.probe', '1.0.0', 'RUNTIME', '{}',
                        'd', 'd', 'd', 'd', 'R4', true, true, 'NON_RETRYABLE', 1, 'QUEUED', 0,
                        CURRENT_TIMESTAMP)
                    """, invocationId, runId, AgentTestDatabase.TENANT_ID, instanceId, packageVersionId))
                    .as("引用 %s 的待审批调用应能落库", packageVersionId)
                    .doesNotThrowAnyException();
        }
    }

    private void seedRun(JdbcTemplate jdbc, String runId, String instanceId) {
        jdbc.update("""
                insert into agent_run (id, tenant_id, human_principal_id, objective, execution_mode,
                    target_scope_json, target_scope_digest, grant_snapshot_json, status, input_revision,
                    budget_json, usage_json, created_at, updated_at)
                values (?, ?, ?, 'obj', 'CAUTIOUS', '{}', 'd', '{}', 'RUNNING', 0, '{}', '{}',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, runId, AgentTestDatabase.TENANT_ID, AgentTestDatabase.USER_ID);
        jdbc.update("""
                insert into agent_instance (id, run_id, tenant_id, instance_name, avatar_seed,
                    definition_snapshot_json, objective, depth, status, reasoning_level,
                    grant_snapshot_json, grant_digest, budget_json, usage_json, created_at, updated_at)
                values (?, ?, ?, 'root', 'seed', '{}', 'obj', 0, 'RUNNING', 'LOW', '{}', 'd', '{}', '{}',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, instanceId, runId, AgentTestDatabase.TENANT_ID);
    }
}

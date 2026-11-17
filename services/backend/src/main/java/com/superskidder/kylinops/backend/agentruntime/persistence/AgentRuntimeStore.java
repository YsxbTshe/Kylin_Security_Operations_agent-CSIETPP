package com.superskidder.kylinops.backend.agentruntime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.context.CompiledContext;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGatewayResult;
import com.superskidder.kylinops.backend.agentruntime.model.RoutedModel;
import com.superskidder.kylinops.backend.agentruntime.security.CanonicalJson;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import com.superskidder.kylinops.backend.agentruntime.tool.ServiceStatusTool;
import com.superskidder.kylinops.backend.agentruntime.transport.NodeDispatchRequest;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AgentRuntimeStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AgentRunEventRepository eventRepository;
    private final String workerId;
    private final Duration leaseDuration;
    private final boolean postgres;

    public AgentRuntimeStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AgentRunEventNotifier eventNotifier,
            DataSource dataSource,
            @Value("${app.agent-runtime.worker-id:backend-1}") String workerId,
            @Value("${app.agent-runtime.lease-seconds:30}") long leaseSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventRepository = new AgentRunEventRepository(jdbcTemplate, objectMapper, eventNotifier);
        this.workerId = workerId;
        this.leaseDuration = Duration.ofSeconds(Math.max(10, leaseSeconds));
        this.postgres = databaseProduct(dataSource).contains("postgresql");
    }

    @Transactional
    public CreatedRun createReadOnlyRun(
            String tenantId,
            String humanPrincipalId,
            String objective,
            String nodeId,
            String service,
            String explicitModelProfileId,
            String reasoningLevel
    ) {
        Instant now = Instant.now();
        String runId = id("run");
        String agentId = id("agent");
        // 目标节点来自 workspace 选择；目标服务可空（由自然语言意图推断，不再强制），空串兜底避免 NPE。
        String node = nodeId == null ? "" : nodeId.trim();
        String svc = service == null ? "" : service.trim();
        Map<String, Object> targetScope = Map.of("nodeId", node, "service", svc);
        String targetJson = canonical(targetScope);
        String targetDigest = Sm3.hex(targetJson);
        Map<String, Object> grant = Map.of(
                "tools", List.of(ServiceStatusTool.TOOL_ID),
                "nodes", List.of(node),
                "readOnly", true
        );
        String grantJson = canonical(grant);
        String grantDigest = Sm3.hex(grantJson);
        long maxInputTokens = Math.min(16_384, Math.max(4_096, 4_096L + objective.length() * 2L));
        String budgetJson = canonical(Map.of(
                // 一次只读诊断至少 3 次模型计量：规划轮 + 工具派发包裹轮 + 终态收敛轮
                // （见 ReadOnlyAgentRuntimeTest 的 agentic 循环契约）。预算真正执行后，
                // 原先的 1/1 会在第二轮入口被 BUDGET_EXCEEDED 杀掉。
                "maxModelInvocations", 8,
                "maxToolInvocations", 8,
                "maxInputTokens", maxInputTokens,
                "maxOutputTokens", 1_024,
                "maxWallClockSeconds", 90,
                "maxWriteActions", 0,
                "maxStagnantRounds", 3
        ));
        String usageJson = canonical(Map.of("modelInvocations", 0, "toolInvocations", 0,
                "inputTokens", 0, "outputTokens", 0));
        String mode = "PLAN";

        jdbcTemplate.update("""
                insert into agent_run (
                    id, tenant_id, human_principal_id, objective, execution_mode, target_scope_json,
                    target_scope_digest, grant_version, grant_snapshot_json, effective_tool_catalog_digest,
                    status, input_revision, budget_json, usage_json, wakeup_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'QUEUED', 0, ?, ?, ?, ?, ?)
                """, runId, tenantId, humanPrincipalId, objective.trim(), mode, targetJson, targetDigest,
                grantJson, ServiceStatusTool.CATALOG_DIGEST, budgetJson, usageJson,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                insert into agent_instance (
                    id, run_id, tenant_id, instance_name, avatar_seed, definition_snapshot_json,
                    objective, depth, status, model_profile_id, reasoning_level, grant_snapshot_json,
                    grant_digest, budget_json, usage_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, 0, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?)
                """, agentId, runId, tenantId, "Root Task Agent", "root-task-agent",
                canonical(Map.of("template", "root-task-agent-v1", "directDisplayOnly", true)), objective.trim(),
                blankToNull(explicitModelProfileId), normalizeReasoning(reasoningLevel), grantJson, grantDigest,
                budgetJson, usageJson, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("update agent_run set root_agent_instance_id = ? where id = ?", agentId, runId);
        appendEvent(runId, tenantId, "RUN_CREATED", "HUMAN", humanPrincipalId,
                Map.of("objective", objective.trim(), "nodeId", node, "service", svc,
                        "executionMode", mode, "budget", readMap(budgetJson)));
        return new CreatedRun(runId, agentId, "QUEUED", budgetJson);
    }

    @Transactional
    public CreatedRun createWriteRun(
            String tenantId,
            String humanPrincipalId,
            String objective,
            String executionMode,
            Map<String, Object> targetScope,
            Map<String, Object> grant,
            String explicitModelProfileId,
            String reasoningLevel
    ) {
        Instant now = Instant.now();
        String runId = id("run");
        String agentId = id("agent");
        String targetJson = canonical(targetScope);
        String targetDigest = Sm3.hex(targetJson);
        String grantJson = canonical(grant);
        String grantDigest = Sm3.hex(grantJson);
        String budgetJson = canonical(Map.of(
                "maxModelInvocations", 10,
                "maxToolInvocations", 20,
                "maxInputTokens", 65_536,
                "maxOutputTokens", 16_384,
                "maxWallClockSeconds", 600,
                // ADR-001 §2.9 第五类预算（写次数）与停滞轮数，worker 缺省也会按同值执行；
                // 显式落库让审批/审计界面可见「这次任务最多允许改多少东西」。
                "maxWriteActions", 10,
                "maxStagnantRounds", 3
        ));
        String usageJson = canonical(Map.of("modelInvocations", 0, "toolInvocations", 0,
                "inputTokens", 0, "outputTokens", 0));
        String mode = normalizeExecutionMode(executionMode);

        jdbcTemplate.update("""
                insert into agent_run (
                    id, tenant_id, human_principal_id, objective, execution_mode, target_scope_json,
                    target_scope_digest, grant_version, grant_snapshot_json, effective_tool_catalog_digest,
                    status, input_revision, budget_json, usage_json, wakeup_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'QUEUED', 0, ?, ?, ?, ?, ?)
                """, runId, tenantId, humanPrincipalId, objective.trim(), mode, targetJson, targetDigest,
                grantJson, "", budgetJson, usageJson,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                insert into agent_instance (
                    id, run_id, tenant_id, instance_name, avatar_seed, definition_snapshot_json,
                    objective, depth, status, model_profile_id, reasoning_level, grant_snapshot_json,
                    grant_digest, budget_json, usage_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, 0, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?)
                """, agentId, runId, tenantId, "Root Task Agent", "root-task-agent",
                canonical(Map.of("template", "root-task-agent-v1")), objective.trim(),
                blankToNull(explicitModelProfileId), normalizeReasoning(reasoningLevel), grantJson, grantDigest,
                budgetJson, usageJson, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("update agent_run set root_agent_instance_id = ? where id = ?", agentId, runId);
        appendEvent(runId, tenantId, "RUN_CREATED", "HUMAN", humanPrincipalId,
                Map.of("objective", objective.trim(), "executionMode", mode,
                        "budget", readMap(budgetJson)));
        return new CreatedRun(runId, agentId, "QUEUED", budgetJson);
    }

    public record RunSecuritySnapshot(
            String id, String tenantId, String humanPrincipalId, String executionMode,
            String targetScopeJson, String grantSnapshotJson, String budgetJson
    ) {}

    /** run 的 scope/grant/budget 原始快照，供 Plan launch 做收窄校验。 */
    public Optional<RunSecuritySnapshot> findRunSecuritySnapshot(String runId) {
        List<RunSecuritySnapshot> rows = jdbcTemplate.query("""
                select id, tenant_id, human_principal_id, execution_mode,
                       target_scope_json, grant_snapshot_json, budget_json
                from agent_run where id = ?
                """, (rs, rowNum) -> new RunSecuritySnapshot(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("human_principal_id"), rs.getString("execution_mode"),
                rs.getString("target_scope_json"), rs.getString("grant_snapshot_json"),
                rs.getString("budget_json")), runId);
        return rows.stream().findFirst();
    }

    /** 从 plan_version 启动新 run：与 createWriteRun 同路径，回填 source_plan_version_id。 */
    @Transactional
    public CreatedRun createRunFromPlan(
            String tenantId,
            String humanPrincipalId,
            String sourcePlanVersionId,
            String objective,
            String executionMode,
            Map<String, Object> targetScope,
            Map<String, Object> grant,
            Map<String, Object> budget,
            String explicitModelProfileId,
            String reasoningLevel
    ) {
        Instant now = Instant.now();
        String runId = id("run");
        String agentId = id("agent");
        String targetJson = canonical(targetScope);
        String targetDigest = Sm3.hex(targetJson);
        String grantJson = canonical(grant);
        String grantDigest = Sm3.hex(grantJson);
        String budgetJson = canonical(budget);
        String usageJson = canonical(Map.of("modelInvocations", 0, "toolInvocations", 0,
                "inputTokens", 0, "outputTokens", 0));
        String mode = normalizeExecutionMode(executionMode);

        jdbcTemplate.update("""
                insert into agent_run (
                    id, tenant_id, source_plan_version_id, human_principal_id, objective, execution_mode,
                    target_scope_json, target_scope_digest, grant_version, grant_snapshot_json,
                    effective_tool_catalog_digest, status, input_revision, budget_json, usage_json,
                    wakeup_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'QUEUED', 0, ?, ?, ?, ?, ?)
                """, runId, tenantId, sourcePlanVersionId, humanPrincipalId, objective.trim(), mode,
                targetJson, targetDigest, grantJson, "", budgetJson, usageJson,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                insert into agent_instance (
                    id, run_id, tenant_id, instance_name, avatar_seed, definition_snapshot_json,
                    objective, depth, status, model_profile_id, reasoning_level, grant_snapshot_json,
                    grant_digest, budget_json, usage_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, 0, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?)
                """, agentId, runId, tenantId, "Root Task Agent", "root-task-agent",
                canonical(Map.of("template", "root-task-agent-v1")), objective.trim(),
                blankToNull(explicitModelProfileId), normalizeReasoning(reasoningLevel), grantJson, grantDigest,
                budgetJson, usageJson, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("update agent_run set root_agent_instance_id = ? where id = ?", agentId, runId);
        appendEvent(runId, tenantId, "RUN_CREATED", "HUMAN", humanPrincipalId,
                Map.of("objective", objective.trim(), "executionMode", mode,
                        "sourcePlanVersionId", sourcePlanVersionId, "budget", readMap(budgetJson)));
        return new CreatedRun(runId, agentId, "QUEUED", budgetJson);
    }

    /** run 置 WAITING、waiting_reason=APPROVAL（符合 V55 状态机约束）；waitingReason 仅入事件。
     *  清租约：WAITING 期间不应持有租约，否则唤醒转 QUEUED 后 leaseNext 仍会因 lease_until 未过期
     *  而跳过它（表现为“批准后 Agent 愣几十秒才动”）。与 transitionToSubAgentWaiting 对齐。 */
    public void transitionToWaiting(LeasedAgentRun run, String waitingReason) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                update agent_run set status = 'WAITING', waiting_reason = 'APPROVAL',
                    lease_owner = null, lease_until = null, updated_at = ?, version = version + 1
                where id = ? and lease_owner = ?
                """, Timestamp.from(now), run.id(), workerId);
        appendEvent(run.id(), run.tenantId(), "RUN_WAITING", "RUNTIME", workerId,
                Map.of("reason", waitingReason));
    }

    // --- SubAgent recruitment (Phase 5.1 RUNTIME path) ---

    public record AgentInstanceView(String id, String runId, int depth, String status) {}

    public Optional<AgentInstanceView> findAgentInstance(String agentInstanceId) {
        List<AgentInstanceView> rows = jdbcTemplate.query("""
                select id, run_id, depth, status from agent_instance where id = ?
                """, (rs, rowNum) -> new AgentInstanceView(rs.getString("id"), rs.getString("run_id"),
                rs.getInt("depth"), rs.getString("status")), agentInstanceId);
        return rows.stream().findFirst();
    }

    // --- 专家注册表身份桥（V10：agent_instance.registry_agent_key） ---

    /**
     * 该 agent 实例登记的注册表专家 key（{@code app_agent_registry.agent_key}）。
     *
     * <p>空 Optional = 无注册身份（根 Agent、存量 run、未声明专家的招募）。
     * DispatchGate 对该情形不做能力封套判定，行为与接入前一致。
     */
    public Optional<String> findRegistryAgentKey(String agentInstanceId) {
        if (agentInstanceId == null || agentInstanceId.isBlank()) {
            return Optional.empty();
        }
        List<String> rows = jdbcTemplate.query(
                "select registry_agent_key from agent_instance where id = ?",
                (rs, rowNum) -> rs.getString("registry_agent_key"), agentInstanceId);
        return rows.stream().filter(key -> key != null && !key.isBlank()).findFirst();
    }

    /**
     * 绑定专家身份。**只在 key 尚为空时写入**（{@code registry_agent_key is null} 条件）：
     * 身份一旦确立就不可改写，否则一个已被攻陷的 Agent 只要改一次自己的 key 就能换一副
     * 更宽的封套——那样这道判定就白做了。
     *
     * @return true 表示本次写入生效
     */
    public boolean bindRegistryAgentKey(String agentInstanceId, String agentKey) {
        if (agentInstanceId == null || agentKey == null || agentKey.isBlank()) {
            return false;
        }
        return jdbcTemplate.update("""
                update agent_instance set registry_agent_key = ?, updated_at = ?
                where id = ? and registry_agent_key is null
                """, agentKey.trim(), Timestamp.from(Instant.now()), agentInstanceId) > 0;
    }

    /** depth>0 的 SubAgent 计数；activeOnly 时仅统计 QUEUED/RUNNING/WAITING。 */
    public int countSubAgents(String runId, boolean activeOnly) {
        String sql = activeOnly
                ? "select count(*) from agent_instance where run_id = ? and depth > 0 and status in ('QUEUED','RUNNING','WAITING')"
                : "select count(*) from agent_instance where run_id = ? and depth > 0";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, runId);
        return count == null ? 0 : count;
    }

    @Transactional
    public String insertSubAgentInstance(
            LeasedAgentRun parentRun,
            String parentAgentInstanceId,
            int depth,
            String goal,
            String definitionSnapshotJson,
            Map<String, Object> childGrant,
            Map<String, Object> childBudget
    ) {
        Instant now = Instant.now();
        String agentId = id("agent");
        String grantJson = canonical(childGrant);
        String budgetJson = canonical(childBudget);
        String usageJson = canonical(Map.of("modelInvocations", 0, "toolInvocations", 0,
                "inputTokens", 0, "outputTokens", 0));
        jdbcTemplate.update("""
                insert into agent_instance (
                    id, run_id, tenant_id, parent_agent_instance_id, instance_name, avatar_seed,
                    definition_snapshot_json, objective, depth, status, reasoning_level,
                    grant_snapshot_json, grant_digest, budget_json, usage_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', 'OFF', ?, ?, ?, ?, ?, ?)
                """, agentId, parentRun.id(), parentRun.tenantId(), parentAgentInstanceId,
                "Sub Agent", "sub-agent", definitionSnapshotJson, goal, depth,
                grantJson, Sm3.hex(grantJson), budgetJson, usageJson,
                Timestamp.from(now), Timestamp.from(now));
        return agentId;
    }

    @Transactional
    public String insertSubAgentRun(
            LeasedAgentRun parentRun,
            String rootAgentInstanceId,
            String goal,
            Map<String, Object> childGrant,
            Map<String, Object> childBudget
    ) {
        Instant now = Instant.now();
        String runId = id("run");
        String targetJson = parentRun.targetScopeJson();
        String grantJson = canonical(childGrant);
        String budgetJson = canonical(childBudget);
        String usageJson = canonical(Map.of("modelInvocations", 0, "toolInvocations", 0,
                "inputTokens", 0, "outputTokens", 0));
        jdbcTemplate.update("""
                insert into agent_run (
                    id, tenant_id, parent_run_id, human_principal_id, objective, execution_mode,
                    target_scope_json, target_scope_digest, grant_version, grant_snapshot_json,
                    status, input_revision, budget_json, usage_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 'QUEUED', 0, ?, ?, ?, ?)
                """, runId, parentRun.tenantId(), parentRun.id(), parentRun.humanPrincipalId(),
                goal, parentRun.executionMode(), targetJson, Sm3.hex(targetJson), grantJson,
                budgetJson, usageJson, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update(
                "update agent_run set root_agent_instance_id = ? where id = ?", rootAgentInstanceId, runId);
        appendEvent(runId, parentRun.tenantId(), "RUN_CREATED", "AGENT", parentRun.id(),
                Map.of("objective", goal, "parentRunId", parentRun.id()));
        return runId;
    }

    /** 父 run 置 WAITING、waiting_reason=SUB_AGENT（符合 V55 状态机约束）。 */
    public void transitionToSubAgentWaiting(LeasedAgentRun run) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                update agent_run set status = 'WAITING', waiting_reason = 'SUB_AGENT',
                    lease_owner = null, lease_until = null, updated_at = ?, version = version + 1
                where id = ? and lease_owner = ?
                """, Timestamp.from(now), run.id(), workerId);
        appendEvent(run.id(), run.tenantId(), "RUN_WAITING", "RUNTIME", workerId,
                Map.of("reason", "SUB_AGENT"));
    }

    /** 审批批准唤醒：WAITING/APPROVAL → QUEUED（符合 V55 状态机约束，waiting_reason 置空）。 */
    public int wakeupRun(String runId) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                update agent_run set status = 'QUEUED', waiting_reason = null, wakeup_at = ?,
                    lease_owner = null, lease_until = null, updated_at = ?, version = version + 1
                where id = ? and status = 'WAITING' and waiting_reason = 'APPROVAL'
                """, Timestamp.from(now), Timestamp.from(now), runId);
        if (updated > 0) {
            String tenantId = jdbcTemplate.queryForObject(
                    "select tenant_id from agent_run where id = ?", String.class, runId);
            appendEvent(runId, tenantId, "RUN_WAKEUP", "RUNTIME", workerId, Map.of());
        }
        return updated;
    }

    /**
     * 解析发起人当前的角色码，供 DispatchGate 对 agent-tool 做 ABAC 判定。
     * worker 异步执行、发起请求的会话已不在，故按 human_principal_id 实时查库。
     * 无角色（或用户已删）→ 空列表 → ABAC 仅按 USER 策略判定，默认 fail-closed。
     */
    public List<String> resolveRoleCodes(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                "select role_code from app_user_role where user_id = ?", String.class, userId);
    }

    // --- SubAgent 完成闭环（Phase 5.3） ---

    public record RunCore(
            String id, String tenantId, String parentRunId, String rootAgentInstanceId,
            String status, long inputRevision
    ) {}

    public record ArtifactRef(String artifactId, String name, String sm3Hex) {}

    public Optional<RunCore> findRunCore(String runId) {
        List<RunCore> rows = jdbcTemplate.query("""
                select id, tenant_id, parent_run_id, root_agent_instance_id, status, input_revision
                from agent_run where id = ?
                """, (rs, rowNum) -> new RunCore(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("parent_run_id"), rs.getString("root_agent_instance_id"),
                rs.getString("status"), rs.getLong("input_revision")), runId);
        return rows.stream().findFirst();
    }

    /** 子实例当前 OPEN 的 handoff（handoff 对 child_agent_instance_id 唯一）。 */
    public Optional<String> findOpenHandoffIdForChild(String childAgentInstanceId) {
        List<String> rows = jdbcTemplate.query("""
                select id from handoff where child_agent_instance_id = ? and status = 'OPEN'
                """, (rs, rowNum) -> rs.getString("id"), childAgentInstanceId);
        return rows.stream().findFirst();
    }

    public List<String> findChildRunIds(String runId) {
        return jdbcTemplate.query("""
                select id from agent_run where parent_run_id = ? order by created_at asc
                """, (rs, rowNum) -> rs.getString("id"), runId);
    }

    public int countActiveChildRuns(String runId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from agent_run
                where parent_run_id = ? and status in ('QUEUED', 'RUNNING', 'WAITING')
                """, Integer.class, runId);
        return count == null ? 0 : count;
    }

    public String finalResultJson(String runId) {
        List<String> rows = jdbcTemplate.query(
                "select final_result_json from agent_run where id = ?",
                (rs, rowNum) -> rs.getString("final_result_json"), runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 招募时从父预算中预扣子预算（逐维 clamp ≥ 0）；终态时按未用部分归还，保证不放大。 */
    public void deductRunBudget(String runId, Map<String, Object> amount) {
        adjustRunBudget(runId, amount, -1);
    }

    public void refundRunBudget(String runId, Map<String, Object> amount) {
        adjustRunBudget(runId, amount, 1);
    }

    private void adjustRunBudget(String runId, Map<String, Object> amount, int sign) {
        String current = jdbcTemplate.queryForObject(
                "select budget_json from agent_run where id = ?", String.class, runId);
        Map<String, Object> budget = new LinkedHashMap<>(readMap(current));
        for (Map.Entry<String, Object> entry : amount.entrySet()) {
            if (!(entry.getValue() instanceof Number delta)) {
                continue;
            }
            long currentDimension = budget.get(entry.getKey()) instanceof Number value ? value.longValue() : 0L;
            budget.put(entry.getKey(), Math.max(0, currentDimension + sign * delta.longValue()));
        }
        jdbcTemplate.update("update agent_run set budget_json = ?, updated_at = ? where id = ?",
                canonical(budget), Timestamp.from(Instant.now()), runId);
    }

    /** 父 run 从 WAITING/SUB_AGENT 回到 QUEUED 可被 lease（waiting_reason 随状态机置空）。 */
    public int wakeFromSubAgentWaiting(String runId) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                update agent_run set status = 'QUEUED', waiting_reason = null, wakeup_at = ?,
                    updated_at = ?, version = version + 1
                where id = ? and status = 'WAITING' and waiting_reason = 'SUB_AGENT'
                """, Timestamp.from(now), Timestamp.from(now), runId);
        if (updated > 0) {
            String tenantId = jdbcTemplate.queryForObject(
                    "select tenant_id from agent_run where id = ?", String.class, runId);
            appendEvent(runId, tenantId, "RUN_WAKEUP", "RUNTIME", workerId, Map.of("reason", "SUB_AGENT"));
        }
        return updated;
    }

    /** 非终态 run → CANCELLED/NOT_ACHIEVED；清理租约与 waiting_reason，联动根实例状态。 */
    @Transactional
    public boolean cancelRunById(String runId, String reason) {
        Instant now = Instant.now();
        String finalJson = canonical(Map.of("errorCode", "RUN_CANCELLED", "message", safeMessage(reason)));
        int updated = jdbcTemplate.update("""
                update agent_run set status = 'CANCELLED', outcome = 'NOT_ACHIEVED', final_result_json = ?,
                    waiting_reason = null, lease_owner = null, lease_until = null,
                    completed_at = ?, updated_at = ?, version = version + 1
                where id = ? and status in ('QUEUED', 'RUNNING', 'WAITING')
                """, finalJson, Timestamp.from(now), Timestamp.from(now), runId);
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update("""
                update agent_instance set status = 'CANCELLED', completed_at = ?, updated_at = ?
                where id = (select root_agent_instance_id from agent_run where id = ?)
                  and status in ('QUEUED', 'RUNNING', 'WAITING')
                """, Timestamp.from(now), Timestamp.from(now), runId);
        jdbcTemplate.update("delete from agent_run_checkpoint where run_id = ?", runId);
        String tenantId = jdbcTemplate.queryForObject(
                "select tenant_id from agent_run where id = ?", String.class, runId);
        appendEvent(runId, tenantId, "RUN_CANCELLED", "RUNTIME", workerId,
                Map.of("reason", safeMessage(reason)));
        return true;
    }

    /** 子实例已发布的 artifact 引用（id + SM3 + 名称），父唤醒时只拿引用不读内容。 */
    public List<ArtifactRef> publishedArtifactRefs(String tenantId, String agentInstanceId) {
        return jdbcTemplate.query("""
                select id, name, sm3_hex from agent_artifact
                where tenant_id = ? and agent_instance_id = ? and status = 'PUBLISHED'
                order by created_at asc
                """, (rs, rowNum) -> new ArtifactRef(rs.getString("id"), rs.getString("name"),
                rs.getString("sm3_hex")), tenantId, agentInstanceId);
    }

    /**
     * 子终态结果写入父上下文帧：仅含 handoff 结果摘要与 artifact 引用（红线：不复制子原始上下文）。
     */
    @Transactional
    public String saveSubAgentWakeContext(
            RunCore parentRun, String agentInstanceId, Map<String, Object> payload
    ) {
        String id = id("ctx");
        String manifest = canonical(payload);
        String digest = Sm3.hex(manifest);
        String provenance = json(List.of(Map.of("kind", "sub-agent-result", "digest", digest)));
        jdbcTemplate.update("""
                insert into context_frame (
                    id, run_id, tenant_id, agent_instance_id, input_revision, stable_prefix_digest,
                    dynamic_suffix_digest, context_digest, segment_manifest_json, provenance_json,
                    token_estimate, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, parentRun.id(), parentRun.tenantId(), agentInstanceId, parentRun.inputRevision(),
                digest, digest, digest, manifest, provenance, Math.max(1, manifest.length() / 4),
                Timestamp.from(Instant.now()));
        appendEvent(parentRun.id(), parentRun.tenantId(), "CONTEXT_COMPILED", "RUNTIME", workerId,
                Map.of("contextFrameId", id, "contextDigest", digest,
                        "kind", "sub-agent-result", "tokenEstimate", Math.max(1, manifest.length() / 4)));
        return id;
    }

    // --- 会话消息持久化与阶段压缩（Phase 6.1） ---

    public record RunMessage(long sequenceNo, Map<String, Object> message, long tokenEstimate) {}

    public long nextMessageSequence(String runId) {
        Long max = jdbcTemplate.queryForObject("""
                select coalesce(max(sequence_no), 0) from context_frame
                where run_id = ? and frame_kind = 'MESSAGE'
                """, Long.class, runId);
        return (max == null ? 0L : max) + 1;
    }

    /** 每轮 tool call/observation 追加为 MESSAGE 行；重启/唤醒后按序号重建。 */
    @Transactional
    public RunMessage appendRunMessage(LeasedAgentRun run, long sequenceNo, Map<String, Object> message) {
        String id = id("msg");
        String manifest = canonical(message);
        String digest = Sm3.hex(manifest);
        long tokenEstimate = Math.max(1, (manifest.length() + 3L) / 4L);
        jdbcTemplate.update("""
                insert into context_frame (
                    id, run_id, tenant_id, agent_instance_id, input_revision, stable_prefix_digest,
                    dynamic_suffix_digest, context_digest, segment_manifest_json, provenance_json,
                    token_estimate, frame_kind, message_role, sequence_no, compressed, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MESSAGE', ?, ?, false, ?)
                """, id, run.id(), run.tenantId(), run.rootAgentInstanceId(), run.inputRevision(),
                digest, digest, digest, manifest, json(List.of(Map.of("kind", "conversation-message"))),
                tokenEstimate, String.valueOf(message.getOrDefault("role", "")), sequenceNo,
                Timestamp.from(Instant.now()));
        return new RunMessage(sequenceNo, new LinkedHashMap<>(message), tokenEstimate);
    }

    public List<RunMessage> loadActiveRunMessages(String runId) {
        return jdbcTemplate.query("""
                select sequence_no, segment_manifest_json, token_estimate from context_frame
                where run_id = ? and frame_kind = 'MESSAGE' and compressed = false
                order by sequence_no asc
                """, (rs, rowNum) -> new RunMessage(rs.getLong("sequence_no"),
                readMap(rs.getString("segment_manifest_json")), rs.getLong("token_estimate")), runId);
    }

    /** 写入 SUMMARY 帧并把被压缩的原始消息标记 compressed（不再发送）。 */
    @Transactional
    public String saveContextSummary(
            LeasedAgentRun run, long sequenceNo, String summaryText,
            List<Long> compressedSequences, long tokenEstimate, long tokensSaved
    ) {
        if (!compressedSequences.isEmpty()) {
            Object[] args = new Object[compressedSequences.size() + 1];
            args[0] = run.id();
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < compressedSequences.size(); i++) {
                args[i + 1] = compressedSequences.get(i);
                placeholders.append(placeholders.isEmpty() ? "?" : ",?");
            }
            jdbcTemplate.update("""
                    update context_frame set compressed = true
                    where run_id = ? and frame_kind = 'MESSAGE' and sequence_no in (%s)
                    """.formatted(placeholders), args);
        }
        String id = id("ctx");
        String manifestJson = canonical(Map.of(
                "kind", "context-summary",
                "summaryText", summaryText,
                "compressedSequences", compressedSequences,
                "tokensSaved", Math.max(0, tokensSaved)));
        String digest = Sm3.hex(manifestJson);
        jdbcTemplate.update("""
                insert into context_frame (
                    id, run_id, tenant_id, agent_instance_id, input_revision, stable_prefix_digest,
                    dynamic_suffix_digest, context_digest, segment_manifest_json, provenance_json,
                    token_estimate, frame_kind, sequence_no, compressed, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUMMARY', ?, false, ?)
                """, id, run.id(), run.tenantId(), run.rootAgentInstanceId(), run.inputRevision(),
                digest, digest, digest, manifestJson, json(List.of(Map.of("kind", "context-summary"))),
                tokenEstimate, sequenceNo, Timestamp.from(Instant.now()));
        appendEvent(run.id(), run.tenantId(), "CONTEXT_SUMMARIZED", "RUNTIME", workerId,
                Map.of("contextFrameId", id, "sequenceNo", sequenceNo,
                        "compressedMessageCount", compressedSequences.size(),
                        "tokenEstimate", tokenEstimate, "tokensSaved", Math.max(0, tokensSaved)));
        return id;
    }

    public String latestSummaryText(String runId) {
        List<String> rows = jdbcTemplate.query("""
                select segment_manifest_json from context_frame
                where run_id = ? and frame_kind = 'SUMMARY'
                order by sequence_no desc, created_at desc limit 1
                """, (rs, rowNum) -> rs.getString("segment_manifest_json"), runId);
        if (rows.isEmpty()) {
            return null;
        }
        Object summaryText = readMap(rows.get(0)).get("summaryText");
        return summaryText == null ? null : String.valueOf(summaryText);
    }

    // --- Checkpoint 恢复支撑（Phase 6.2） ---

    /** run 最近一次 tool_invocation id（检查点内容）；无则返回 null。 */
    public String lastToolInvocationId(String runId) {
        List<String> rows = jdbcTemplate.query("""
                select id from tool_invocation where run_id = ?
                order by queued_at desc, id desc limit 1
                """, (rs, rowNum) -> rs.getString("id"), runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record CompletedToolInvocation(String id, String resultJson) {}

    /** 同 run 内已成功执行过的同工具同参调用（重启不重放已完成工具）。 */
    public Optional<CompletedToolInvocation> findSucceededToolInvocation(
            String runId, String toolId, String argumentsDigest
    ) {
        List<CompletedToolInvocation> rows = jdbcTemplate.query("""
                select id, result_json from tool_invocation
                where run_id = ? and tool_id = ? and arguments_digest = ? and status = 'SUCCEEDED'
                order by queued_at desc, id desc limit 1
                """, (rs, rowNum) -> new CompletedToolInvocation(rs.getString("id"),
                rs.getString("result_json")), runId, toolId, argumentsDigest);
        return rows.stream().findFirst();
    }

    private String normalizeExecutionMode(String mode) {
        if (mode == null || mode.isBlank()) return "PLAN";
        return switch (mode.trim().toUpperCase()) {
            case "AUTO", "CAUTIOUS", "PLAN" -> mode.trim().toUpperCase();
            default -> "PLAN";
        };
    }

    @Transactional
    public Optional<LeasedAgentRun> leaseNext() {
        Instant now = Instant.now();
        String suffix = postgres ? " limit 1 for update skip locked" : " limit 1 for update";
        // 租户额度（Phase 6.2）：活跃并发 = 持有有效租约的 RUNNING run；超额租户保持 QUEUED 不租出。
        List<LeasedAgentRun> rows = jdbcTemplate.query("""
                select ar.id, ar.tenant_id, ar.human_principal_id, ar.root_agent_instance_id, ar.objective,
                       ar.execution_mode, ar.target_scope_json, ar.grant_snapshot_json, ar.budget_json,
                       ar.input_revision, ai.model_profile_id, ai.reasoning_level
                from agent_run ar join agent_instance ai on ai.id = ar.root_agent_instance_id
                where ar.status in ('QUEUED', 'RUNNING')
                  and (ar.wakeup_at is null or ar.wakeup_at <= ?)
                  and (ar.lease_until is null or ar.lease_until < ?)
                  and (select count(*) from agent_run peer
                       where peer.tenant_id = ar.tenant_id and peer.status = 'RUNNING'
                         and peer.id <> ar.id and peer.lease_until is not null and peer.lease_until >= ?)
                      < coalesce((select q.max_concurrent_runs from tenant_runtime_quota q
                                  where q.tenant_id = ar.tenant_id), 8)
                  and (ar.parent_run_id is null
                       or (select count(*) from agent_run peer
                           where peer.tenant_id = ar.tenant_id and peer.parent_run_id is not null
                             and peer.status = 'RUNNING' and peer.id <> ar.id
                             and peer.lease_until is not null and peer.lease_until >= ?)
                          < coalesce((select q.max_concurrent_subagents from tenant_runtime_quota q
                                      where q.tenant_id = ar.tenant_id), 4))
                order by ar.created_at asc
                """ + suffix, (rs, rowNum) -> new LeasedAgentRun(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("human_principal_id"),
                rs.getString("root_agent_instance_id"), rs.getString("objective"), rs.getString("execution_mode"),
                rs.getString("target_scope_json"), rs.getString("grant_snapshot_json"), rs.getString("budget_json"),
                rs.getLong("input_revision"), rs.getString("model_profile_id"), rs.getString("reasoning_level")),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        LeasedAgentRun run = rows.get(0);
        jdbcTemplate.update("""
                update agent_run set status = 'RUNNING', started_at = coalesce(started_at, ?),
                    lease_owner = ?, lease_until = ?, version = version + 1, updated_at = ?
                where id = ?
                """, Timestamp.from(now), workerId, Timestamp.from(now.plus(leaseDuration)), Timestamp.from(now), run.id());
        jdbcTemplate.update("""
                update agent_instance set status = 'RUNNING', started_at = coalesce(started_at, ?), updated_at = ?
                where id = ?
                """, Timestamp.from(now), Timestamp.from(now), run.rootAgentInstanceId());
        appendEvent(run.id(), run.tenantId(), "RUN_STARTED", "RUNTIME", workerId,
                Map.of("leaseOwner", workerId, "leaseUntil", now.plus(leaseDuration).toString()));
        return Optional.of(run);
    }

    @Transactional
    public LeasedAgentRun leaseSpecific(String runId) {
        Instant now = Instant.now();
        List<LeasedAgentRun> rows = jdbcTemplate.query("""
                select ar.id, ar.tenant_id, ar.human_principal_id, ar.root_agent_instance_id, ar.objective,
                       ar.execution_mode, ar.target_scope_json, ar.grant_snapshot_json, ar.budget_json,
                       ar.input_revision, ai.model_profile_id, ai.reasoning_level
                from agent_run ar join agent_instance ai on ai.id = ar.root_agent_instance_id
                where ar.id = ? and ar.status = 'QUEUED' for update
                """, (rs, rowNum) -> new LeasedAgentRun(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("human_principal_id"),
                rs.getString("root_agent_instance_id"), rs.getString("objective"), rs.getString("execution_mode"),
                rs.getString("target_scope_json"), rs.getString("grant_snapshot_json"), rs.getString("budget_json"),
                rs.getLong("input_revision"), rs.getString("model_profile_id"), rs.getString("reasoning_level")), runId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("AgentRun is not available for lease: " + runId);
        }
        LeasedAgentRun run = rows.get(0);
        jdbcTemplate.update("""
                update agent_run set status = 'RUNNING', started_at = ?, lease_owner = ?, lease_until = ?,
                    version = version + 1, updated_at = ? where id = ?
                """, Timestamp.from(now), workerId, Timestamp.from(now.plus(leaseDuration)), Timestamp.from(now), runId);
        jdbcTemplate.update("update agent_instance set status = 'RUNNING', started_at = ?, updated_at = ? where id = ?",
                Timestamp.from(now), Timestamp.from(now), run.rootAgentInstanceId());
        appendEvent(run.id(), run.tenantId(), "RUN_STARTED", "RUNTIME", workerId,
                Map.of("leaseOwner", workerId, "leaseUntil", now.plus(leaseDuration).toString()));
        return run;
    }

    public void renewLease(String runId, Duration minimumDuration) {
        Duration duration = minimumDuration.compareTo(leaseDuration) > 0 ? minimumDuration : leaseDuration;
        jdbcTemplate.update("""
                update agent_run set lease_until = ?, updated_at = ? where id = ? and lease_owner = ? and status = 'RUNNING'
                """, Timestamp.from(Instant.now().plus(duration)), Timestamp.from(Instant.now()), runId, workerId);
    }

    @Transactional
    public String saveContext(LeasedAgentRun run, CompiledContext context) {
        String id = id("ctx");
        String segmentManifest = json(Map.of("stablePrefix", context.stablePrefix(), "dynamicSuffix", context.dynamicSuffix()));
        String provenance = json(List.of(
                Map.of("kind", "stable-prefix", "digest", context.stablePrefixDigest()),
                Map.of("kind", "dynamic-suffix", "digest", context.dynamicSuffixDigest())
        ));
        jdbcTemplate.update("""
                insert into context_frame (
                    id, run_id, tenant_id, agent_instance_id, input_revision, stable_prefix_digest,
                    dynamic_suffix_digest, context_digest, segment_manifest_json, provenance_json,
                    token_estimate, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, run.id(), run.tenantId(), run.rootAgentInstanceId(), run.inputRevision(),
                context.stablePrefixDigest(), context.dynamicSuffixDigest(), context.contextDigest(),
                segmentManifest, provenance, context.tokenEstimate(), Timestamp.from(Instant.now()));
        appendEvent(run.id(), run.tenantId(), "CONTEXT_COMPILED", "RUNTIME", workerId,
                Map.of("contextFrameId", id, "contextDigest", context.contextDigest(),
                        "stablePrefixDigest", context.stablePrefixDigest(), "prefixCacheHit", context.localPrefixCacheHit(),
                        "tokenEstimate", context.tokenEstimate()));
        return id;
    }

    @Transactional
    public String startModelInvocation(LeasedAgentRun run, String contextFrameId, RoutedModel routed) {
        String id = id("invoke");
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into agent_invocation (
                    id, run_id, tenant_id, agent_instance_id, context_frame_id, input_revision,
                    provider, model, model_config_digest, reasoning_level, status, started_at, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)
                """, id, run.id(), run.tenantId(), run.rootAgentInstanceId(), contextFrameId, run.inputRevision(),
                routed.config().providerType(), routed.config().model(), routed.configDigest(), routed.reasoningLevel(),
                Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                update agent_instance set model_profile_id = ?, model_config_digest = ?, reasoning_level = ?, updated_at = ?
                where id = ?
                """, blankToNull(routed.profileId()), routed.configDigest(), routed.reasoningLevel(), Timestamp.from(now),
                run.rootAgentInstanceId());
        appendEvent(run.id(), run.tenantId(), "MODEL_INVOCATION_STARTED", "RUNTIME", workerId,
                Map.of("agentInvocationId", id, "provider", routed.config().providerType(), "model", routed.config().model(),
                        "reasoningLevel", routed.reasoningLevel(), "routeReason", routed.reason()));
        return id;
    }

    @Transactional
    public void completeModelInvocation(LeasedAgentRun run, String invocationId, ModelGatewayResult result) {
        jdbcTemplate.update("""
                update agent_invocation set status = 'SUCCEEDED', input_tokens = ?, output_tokens = ?,
                    cache_read_tokens = ?, cache_write_tokens = ?, ttft_millis = ?, latency_millis = ?,
                    retry_count = ?, completed_at = ? where id = ?
                """, result.inputTokens(), result.outputTokens(), result.cacheReadTokens(), result.cacheWriteTokens(),
                result.ttftMillis(), result.latencyMillis(), result.retryCount(), Timestamp.from(Instant.now()), invocationId);
        updateUsage(run.id(), 1, 0, result.inputTokens(), result.outputTokens());
        appendEvent(run.id(), run.tenantId(), "MODEL_INVOCATION_COMPLETED", "MODEL", invocationId,
                Map.of("agentInvocationId", invocationId, "toolCallCount", result.toolCalls().size(),
                        "inputTokens", result.inputTokens(), "outputTokens", result.outputTokens(),
                        "cacheReadTokens", result.cacheReadTokens(), "cacheWriteTokens", result.cacheWriteTokens(),
                        "latencyMillis", result.latencyMillis()));
    }

    @Transactional
    public void failModelInvocation(LeasedAgentRun run, String invocationId, String errorCode, String message) {
        int updated = jdbcTemplate.update("""
                update agent_invocation set status = 'FAILED', error_code = ?, error_message = ?, completed_at = ?
                where id = ? and status = 'RUNNING'
                """, errorCode, safeMessage(message), Timestamp.from(Instant.now()), invocationId);
        if (updated > 0) {
            appendEvent(run.id(), run.tenantId(), "MODEL_INVOCATION_FAILED", "RUNTIME", workerId,
                    Map.of("agentInvocationId", invocationId, "errorCode", errorCode, "message", safeMessage(message)));
        }
    }

    @Transactional
    public ToolExecutionRef startServiceStatusTool(
            LeasedAgentRun run,
            String agentInvocationId,
            String nodeId,
            String canonicalArgumentsJson
    ) {
        List<NodeDispatchRequest.LockEntry> locks = List.of(
                new NodeDispatchRequest.LockEntry(
                        "node:" + nodeId + ":tool:" + ServiceStatusTool.TOOL_ID, "READ_SHARED"));
        return startToolInvocation(run, agentInvocationId, nodeId, serviceStatusToolAsNodeTool(),
                canonicalArgumentsJson, locks, 0L);
    }

    @Transactional
    public ToolExecutionRef startToolInvocation(
            LeasedAgentRun run,
            String agentInvocationId,
            String nodeId,
            NodeTool tool,
            String canonicalArgumentsJson,
            List<NodeDispatchRequest.LockEntry> resourceLocks,
            long lockVersion
    ) {
        String toolId = id("tool");
        String attemptId = id("attempt");
        String argsDigest = Sm3.hex(canonicalArgumentsJson);
        String grantDigest = Sm3.hex(run.grantSnapshotJson());
        Instant now = Instant.now();
        String lockJson = json(resourceLocks.stream()
                .map(l -> Map.of("resource", l.resourceKey(), "mode", l.lockMode())).toList());
        jdbcTemplate.update("""
                insert into tool_invocation (
                    id, run_id, tenant_id, agent_instance_id, agent_invocation_id, tool_package_version_id,
                    tool_id, tool_version, executor, target_node_id, arguments_json, arguments_digest,
                    input_schema_digest, output_schema_digest, grant_digest, risk_code, read_only,
                    always_require_approval, idempotency_type, max_attempts, status, input_revision,
                    queued_at, direct_display
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'NODE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?)
                """, toolId, run.id(), run.tenantId(), run.rootAgentInstanceId(), agentInvocationId,
                tool.packageVersionId(), tool.toolId(), tool.version(),
                nodeId, canonicalArgumentsJson, argsDigest, tool.inputSchemaDigest(),
                tool.outputSchemaDigest(), grantDigest, tool.riskFloor(), tool.readOnly(),
                tool.alwaysRequireApproval(), tool.idempotency().name(), tool.maxAttempts(),
                run.inputRevision(), Timestamp.from(now), tool.readOnly());
        String requestDigest = Sm3.hex((toolId + ":" + attemptId + ":" + argsDigest).getBytes(StandardCharsets.UTF_8));
        jdbcTemplate.update("""
                insert into execution_attempt (
                    id, tool_invocation_id, tenant_id, attempt_no, status, node_id, request_digest,
                    lock_version, created_at
                ) values (?, ?, ?, 1, 'QUEUED', ?, ?, ?, ?)
                """, attemptId, toolId, run.tenantId(), nodeId, requestDigest,
                lockVersion > 0 ? lockVersion : null, Timestamp.from(now));
        updateUsage(run.id(), 0, 1, 0, 0);
        appendEvent(run.id(), run.tenantId(), "TOOL_INVOCATION_QUEUED", "MODEL", agentInvocationId,
                Map.of("toolInvocationId", toolId, "executionAttemptId", attemptId,
                        "toolId", tool.toolId(), "nodeId", nodeId, "arguments", readMap(canonicalArgumentsJson),
                        "readOnly", tool.readOnly(), "riskCode", tool.riskFloor()));
        return new ToolExecutionRef(toolId, attemptId, canonicalArgumentsJson);
    }

    /**
     * 审批占位 invocation：请求审批时先落 tool_invocation（QUEUED、无 attempt），
     * 令 approval 的 tool_invocation_id 外键指向将来真正执行的那一次调用。
     * attempt 在批准后 dispatch 时经 {@link #claimAttemptForApprovedInvocation} 一次性产生。
     */
    @Transactional
    public String createAwaitingToolInvocation(
            LeasedAgentRun run,
            String agentInvocationId,
            String nodeId,
            NodeTool tool,
            String canonicalArgumentsJson
    ) {
        String toolId = id("tool");
        String argsDigest = Sm3.hex(canonicalArgumentsJson);
        String grantDigest = Sm3.hex(run.grantSnapshotJson());
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into tool_invocation (
                    id, run_id, tenant_id, agent_instance_id, agent_invocation_id, tool_package_version_id,
                    tool_id, tool_version, executor, target_node_id, arguments_json, arguments_digest,
                    input_schema_digest, output_schema_digest, grant_digest, risk_code, read_only,
                    always_require_approval, idempotency_type, max_attempts, status, input_revision,
                    queued_at, direct_display
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'NODE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?)
                """, toolId, run.id(), run.tenantId(), run.rootAgentInstanceId(), blankToNull(agentInvocationId),
                tool.packageVersionId(), tool.toolId(), tool.version(),
                nodeId, canonicalArgumentsJson, argsDigest, tool.inputSchemaDigest(),
                tool.outputSchemaDigest(), grantDigest, tool.riskFloor(), tool.readOnly(),
                tool.alwaysRequireApproval(), tool.idempotency().name(), tool.maxAttempts(),
                run.inputRevision(), Timestamp.from(now), tool.readOnly());
        updateUsage(run.id(), 0, 1, 0, 0);
        appendEvent(run.id(), run.tenantId(), "TOOL_INVOCATION_QUEUED", "MODEL",
                blankToNull(agentInvocationId),
                Map.of("toolInvocationId", toolId, "toolId", tool.toolId(), "nodeId", nodeId,
                        "arguments", readMap(canonicalArgumentsJson), "readOnly", tool.readOnly(),
                        "riskCode", tool.riskFloor(), "awaitingApproval", true));
        return toolId;
    }

    /**
     * 批准消费的强制点：对占位 invocation 用条件插入认领唯一一个 attempt。
     * 插入成功即「消费」了这次批准（同一 invocation 不会再产生第二个 attempt）；
     * 返回空表示已被消费（重复调用 / 并发竞争），调用方必须重新走审批。
     */
    @Transactional
    public Optional<String> claimAttemptForApprovedInvocation(
            String toolInvocationId, String tenantId, String nodeId, long lockVersion, String argumentsDigest
    ) {
        String attemptId = id("attempt");
        String requestDigest = Sm3.hex(
                (toolInvocationId + ":" + attemptId + ":" + argumentsDigest).getBytes(StandardCharsets.UTF_8));
        int inserted = jdbcTemplate.update("""
                insert into execution_attempt (
                    id, tool_invocation_id, tenant_id, attempt_no, status, node_id, request_digest,
                    lock_version, created_at
                ) select ?, ?, ?, 1, 'QUEUED', ?, ?, ?, ?
                where not exists (select 1 from execution_attempt where tool_invocation_id = ?)
                """, attemptId, toolInvocationId, tenantId, nodeId, requestDigest,
                lockVersion > 0 ? lockVersion : null, Timestamp.from(Instant.now()), toolInvocationId);
        return inserted > 0 ? Optional.of(attemptId) : Optional.empty();
    }

    public record InvocationOutcome(String status, String resultJson, String errorCode) {}

    public Optional<InvocationOutcome> findInvocationOutcome(String toolInvocationId) {
        List<InvocationOutcome> rows = jdbcTemplate.query("""
                select status, result_json, error_code from tool_invocation where id = ?
                """, (rs, rowNum) -> new InvocationOutcome(rs.getString("status"),
                rs.getString("result_json"), rs.getString("error_code")), toolInvocationId);
        return rows.stream().findFirst();
    }

    /** 审批被拒：把占位 invocation 关成 REJECTED，保持审计链上的因果完整。 */
    public void markInvocationRejected(String toolInvocationId, String rejectionCode) {
        jdbcTemplate.update("""
                update tool_invocation set status = 'REJECTED', rejection_code = ?, completed_at = ?
                where id = ? and status = 'QUEUED'
                """, rejectionCode, Timestamp.from(Instant.now()), toolInvocationId);
    }

    /** run 的当前累计用量（modelInvocations/toolInvocations/inputTokens/outputTokens/writeActions）。 */
    public Map<String, Object> readUsage(String runId) {
        String current = jdbcTemplate.queryForObject(
                "select usage_json from agent_run where id = ?", String.class, runId);
        return readMap(current);
    }

    /** 写操作计数（ADR-001 §2.9 第五类预算）：每次非只读工具真实执行前 +1。 */
    public void addWriteActionUsage(String runId) {
        String current = jdbcTemplate.queryForObject(
                "select usage_json from agent_run where id = ?", String.class, runId);
        Map<String, Object> usage = new LinkedHashMap<>(readMap(current));
        add(usage, "writeActions", 1);
        jdbcTemplate.update("update agent_run set usage_json = ?, updated_at = ? where id = ?",
                canonical(usage), Timestamp.from(Instant.now()), runId);
    }

    public record NodeGuardPosture(String status, Instant observedAt) {}

    /** 节点最近一次心跳上报的 Native Guard 姿态；从未上报（或列为空）返回 empty。 */
    public Optional<NodeGuardPosture> findNodeGuardPosture(String tenantId, String nodeId) {
        List<NodeGuardPosture> rows = jdbcTemplate.query("""
                select native_guard_status, native_guard_observed_at
                from agent_runtime_node_session where tenant_id = ? and node_id = ?
                """, (rs, rowNum) -> new NodeGuardPosture(rs.getString("native_guard_status"),
                rs.getTimestamp("native_guard_observed_at") != null
                        ? rs.getTimestamp("native_guard_observed_at").toInstant() : null),
                tenantId, nodeId);
        return rows.stream().filter(p -> p.status() != null && !p.status().isBlank()).findFirst();
    }

    public long getOrIncrementNodeLockVersion(String tenantId, String nodeId) {
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
        List<Long> versions = jdbcTemplate.query("""
                select lock_version from agent_runtime_node_lock_version where tenant_id = ? and node_id = ?
                """, (rs, rowNum) -> rs.getLong("lock_version"), tenantId, nodeId);
        return versions.isEmpty() ? 1L : versions.get(0);
    }

    private NodeTool serviceStatusToolAsNodeTool() {
        return new NodeTool() {
            @Override public String toolId() { return ServiceStatusTool.TOOL_ID; }
            @Override public String version() { return ServiceStatusTool.VERSION; }
            @Override public String packageVersionId() { return ServiceStatusTool.PACKAGE_VERSION_ID; }
            @Override public boolean readOnly() { return true; }
            @Override public String riskFloor() { return "R0"; }
            @Override public boolean alwaysRequireApproval() { return false; }
            @Override public com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency idempotency() {
                return com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency.IDEMPOTENT;
            }
            @Override public int maxAttempts() { return 2; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public Map<String, Object> outputSchema() { return Map.of(); }
            @Override public Map<String, Object> validateArguments(Map<String, Object> a) { return a; }
            @Override public Map<String, Object> validateOutput(String r) { return Map.of(); }
            @Override public Map<String, Object> render(Map<String, Object> o) { return o; }
            @Override public com.superskidder.kylinops.backend.llm.LlmTool llmTool() { return null; }
            @Override public String inputSchemaDigest() { return ServiceStatusTool.INPUT_SCHEMA_DIGEST; }
            @Override public String outputSchemaDigest() { return ServiceStatusTool.OUTPUT_SCHEMA_DIGEST; }
            @Override public String catalogDigest() { return ServiceStatusTool.CATALOG_DIGEST; }
            @Override public List<ResourceLockTemplate> resourceLockTemplates() { return List.of(); }
        };
    }

    public void markAttemptSent(String attemptId, long protocolSequence) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                update execution_attempt set status = 'SENT', protocol_sequence = ?, started_at = coalesce(started_at, ?)
                where id = ? and status = 'QUEUED'
                """, protocolSequence, Timestamp.from(now), attemptId);
        jdbcTemplate.update("""
                update tool_invocation set status = 'RUNNING', started_at = coalesce(started_at, ?)
                where id = (select tool_invocation_id from execution_attempt where id = ?)
                """, Timestamp.from(now), attemptId);
    }

    @Transactional
    public void markAttemptAcknowledged(String attemptId, Instant persistedAt) {
        int updated = jdbcTemplate.update("""
                update execution_attempt set status = case when status = 'SENT' then 'ACK_RECEIVED' else status end,
                    ack_received_at = ? where id = ? and ack_received_at is null
                """, Timestamp.from(persistedAt == null ? Instant.now() : persistedAt), attemptId);
        if (updated == 0) {
            return;
        }
        AttemptOwner owner = attemptOwner(attemptId);
        appendEvent(owner.runId(), owner.tenantId(), "NODE_INVOCATION_ACKNOWLEDGED", "NODE", owner.nodeId(),
                Map.of("toolInvocationId", owner.toolInvocationId(), "executionAttemptId", attemptId));
    }

    @Transactional
    public void recordNodeResult(String attemptId, boolean success, String resultJson, String errorCode,
                                 String message, boolean retryable) {
        AttemptOwner owner = attemptOwner(attemptId);
        Instant now = Instant.now();
        String attemptStatus = success ? "SUCCEEDED" : "FAILED";
        int updated = jdbcTemplate.update("""
                update execution_attempt set status = ?, completed_at = ?, error_code = ?, error_message = ?,
                    retryable = ?, result_json = ? where id = ?
                    and status not in ('SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED')
                """, attemptStatus, Timestamp.from(now), blankToNull(errorCode), blankToNull(message), retryable,
                blankToNull(resultJson), attemptId);
        if (updated == 0) {
            return;
        }
        jdbcTemplate.update("""
                update tool_invocation set status = ?, completed_at = ?, error_code = ?, result_json = ? where id = ?
                """, attemptStatus, Timestamp.from(now), blankToNull(errorCode), blankToNull(resultJson), owner.toolInvocationId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolInvocationId", owner.toolInvocationId());
        payload.put("executionAttemptId", attemptId);
        payload.put("status", attemptStatus);
        payload.put("retryable", retryable);
        if (resultJson != null && !resultJson.isBlank()) {
            payload.put("result", readJson(resultJson));
        }
        if (errorCode != null && !errorCode.isBlank()) {
            payload.put("errorCode", errorCode);
        }
        appendEvent(owner.runId(), owner.tenantId(), success ? "TOOL_INVOCATION_SUCCEEDED" : "TOOL_INVOCATION_FAILED",
                "NODE", owner.nodeId(), payload);
    }

    @Transactional
    public void completeRun(LeasedAgentRun run, Map<String, Object> renderedResult, long controlOverheadMillis) {
        Instant now = Instant.now();
        Map<String, Object> finalResult = new LinkedHashMap<>(renderedResult);
        finalResult.put("controlOverheadMillis", controlOverheadMillis);
        finalResult.put("modelRewriteCount", 0);
        finalResult.put("path", "task-agent>mcp>grpc-v2>trusted-renderer");
        String finalJson = canonical(finalResult);
        int updated = jdbcTemplate.update("""
                update agent_run set status = 'COMPLETED', outcome = 'SUCCEEDED', final_result_json = ?,
                    lease_owner = null, lease_until = null, completed_at = ?, updated_at = ?, version = version + 1
                where id = ? and lease_owner = ?
                """, finalJson, Timestamp.from(now), Timestamp.from(now), run.id(), workerId);
        if (updated == 0) {
            throw new IllegalStateException("AgentRun lease was lost before completion: " + run.id());
        }
        jdbcTemplate.update("""
                update agent_instance set status = 'COMPLETED', completed_at = ?, updated_at = ? where id = ?
                """, Timestamp.from(now), Timestamp.from(now), run.rootAgentInstanceId());
        jdbcTemplate.update("delete from agent_run_checkpoint where run_id = ?", run.id());
        appendEvent(run.id(), run.tenantId(), "RUN_COMPLETED", "RUNTIME", workerId,
                Map.of("outcome", "SUCCEEDED", "result", finalResult));
    }

    @Transactional
    public void failRun(LeasedAgentRun run, String errorCode, String message) {
        Instant now = Instant.now();
        String finalJson = canonical(Map.of("errorCode", errorCode, "message", safeMessage(message)));
        int updated = jdbcTemplate.update("""
                update agent_run set status = 'FAILED', outcome = 'NOT_ACHIEVED', final_result_json = ?,
                    lease_owner = null, lease_until = null, completed_at = ?, updated_at = ?, version = version + 1
                where id = ? and lease_owner = ? and status = 'RUNNING'
                """, finalJson, Timestamp.from(now), Timestamp.from(now), run.id(), workerId);
        if (updated == 0) {
            return;
        }
        jdbcTemplate.update("""
                update agent_instance set status = 'FAILED', completed_at = ?, updated_at = ? where id = ?
                """, Timestamp.from(now), Timestamp.from(now), run.rootAgentInstanceId());
        jdbcTemplate.update("delete from agent_run_checkpoint where run_id = ?", run.id());
        appendEvent(run.id(), run.tenantId(), "RUN_FAILED", "RUNTIME", workerId,
                Map.of("errorCode", errorCode, "message", safeMessage(message)));
    }

    public Optional<RunView> findRun(String tenantId, String runId) {
        List<RunView> rows = jdbcTemplate.query("""
                select id, objective, execution_mode, status, outcome, budget_json, usage_json, final_result_json,
                       created_at, started_at, completed_at
                from agent_run where tenant_id = ? and id = ?
                """, (rs, rowNum) -> new RunView(rs.getString("id"), rs.getString("objective"),
                rs.getString("execution_mode"), rs.getString("status"), rs.getString("outcome"),
                readMap(rs.getString("budget_json")), readMap(rs.getString("usage_json")),
                readNullableMap(rs.getString("final_result_json")), rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at"))), tenantId, runId);
        return rows.stream().findFirst();
    }

    public List<AgentRunEvent> eventsAfter(String tenantId, String runId, long afterSequence, int limit) {
        return eventRepository.eventsAfter(tenantId, runId, afterSequence, limit);
    }

    /**
     * 校验一条 run 的 SM3 哈希链：genesis 必须为 ZERO_HASH，每条
     * event_hash = SM3(prev_hash + 规范化载荷)，且 prev_hash 等于前一条 event_hash。
     */
    public AgentRunEventRepository.ChainVerification verifyChain(String tenantId, String runId) {
        return eventRepository.verifyChain(tenantId, runId);
    }

    @Transactional
    public AgentRunEvent appendEvent(String runId, String tenantId, String eventType, String actorType,
                                     String actorId, Map<String, Object> payload) {
        return eventRepository.appendEvent(runId, tenantId, eventType, actorType, actorId, payload);
    }

    private AttemptOwner attemptOwner(String attemptId) {
        return jdbcTemplate.queryForObject("""
                select ti.id as tool_invocation_id, ti.run_id, ti.tenant_id, ea.node_id
                from execution_attempt ea join tool_invocation ti on ti.id = ea.tool_invocation_id
                where ea.id = ?
                """, (rs, rowNum) -> new AttemptOwner(rs.getString("tool_invocation_id"), rs.getString("run_id"),
                rs.getString("tenant_id"), rs.getString("node_id")), attemptId);
    }

    private void updateUsage(String runId, long modelDelta, long toolDelta, long inputDelta, long outputDelta) {
        String current = jdbcTemplate.queryForObject("select usage_json from agent_run where id = ?", String.class, runId);
        Map<String, Object> usage = new LinkedHashMap<>(readMap(current));
        add(usage, "modelInvocations", modelDelta);
        add(usage, "toolInvocations", toolDelta);
        add(usage, "inputTokens", inputDelta);
        add(usage, "outputTokens", outputDelta);
        jdbcTemplate.update("update agent_run set usage_json = ?, updated_at = ? where id = ?",
                canonical(usage), Timestamp.from(Instant.now()), runId);
    }

    private void add(Map<String, Object> values, String key, long delta) {
        long current = values.get(key) instanceof Number number ? number.longValue() : 0;
        values.put(key, current + delta);
    }

    private String canonical(Object value) {
        return CanonicalJson.canonicalize(json(value));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value cannot be serialized as JSON", exception);
        }
    }

    private Object readJson(String value) {
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid JSON", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid JSON object", exception);
        }
    }

    private Map<String, Object> readNullableMap(String value) {
        return value == null || value.isBlank() ? null : readMap(value);
    }

    private String normalizeReasoning(String value) {
        if (value == null || value.isBlank()) {
            return "OFF";
        }
        String normalized = value.trim().toUpperCase();
        return List.of("OFF", "LOW", "MEDIUM", "HIGH").contains(normalized) ? normalized : "OFF";
    }

    private String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String safeMessage(String value) {
        String message = value == null ? "Agent runtime failed" : value.trim();
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String databaseProduct(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase();
        } catch (Exception exception) {
            return "";
        }
    }

    private record AttemptOwner(String toolInvocationId, String runId, String tenantId, String nodeId) {}

    public record CreatedRun(String runId, String rootAgentInstanceId, String status, String budgetJson) {}

    public record RunView(
            String id, String objective, String executionMode, String status, String outcome,
            Map<String, Object> budget, Map<String, Object> usage, Map<String, Object> finalResult,
            Instant createdAt, Instant startedAt, Instant completedAt
    ) {}
}

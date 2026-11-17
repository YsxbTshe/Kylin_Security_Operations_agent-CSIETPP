package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.agentruntime.AgentRoundExecutor;
import com.superskidder.kylinops.backend.agentruntime.AgentRunWorker;
import com.superskidder.kylinops.backend.agentruntime.ApprovalService;
import com.superskidder.kylinops.backend.agentruntime.checkpoint.RunCheckpointService;
import com.superskidder.kylinops.backend.agentruntime.connector.ConnectorToolDispatcher;
import com.superskidder.kylinops.backend.agentruntime.context.ContextCompiler;
import com.superskidder.kylinops.backend.agentruntime.context.ContextSummarizer;
import com.superskidder.kylinops.backend.agentruntime.context.ContextWindowPolicy;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.memory.MemoryService;
import com.superskidder.kylinops.backend.agentruntime.memory.MemoryStore;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGateway;
import com.superskidder.kylinops.backend.agentruntime.model.RoutedModel;
import com.superskidder.kylinops.backend.agentruntime.model.SimpleModelRouter;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEvent;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.security.DispatchGate;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import com.superskidder.kylinops.backend.agentruntime.tool.ToolRegistry;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.llm.LlmModelConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部模型全部不可用时的确定性只读兜底（README 承诺的"LLM planner 可回退确定性规划"）。
 *
 * <p>此前模型调用失败的唯一出路是 {@code failModelInvocation + failRun}：一个外部依赖抖动
 * 就让整条链路对外表现为"这系统不能用"。本类验收降级后的四条性质：
 * <ol>
 *   <li>run 仍能正常完成，时间线非空；</li>
 *   <li>降级被**显式标注**（专门的审计事件 + 结果里的 degraded 标记），不伪装成模型输出；</li>
 *   <li><b>兜底产生的工具调用全部只读</b>，一次写操作也没有（本次审查的红线）；</li>
 *   <li>关键词匹配不到剧本时诚实失败，不瞎跑。</li>
 * </ol>
 */
class ModelUnavailableFallbackTest {

    private static final List<String> DISK_CHAIN = List.of(
            "kylin.disk.usage", "kylin.system.mounts", "kylin.disk.large_files", "kylin.cleanup.plan");
    private static final Map<String, Object> GRANT = Map.of("nodes", List.of("node-1"), "readOnly", true);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private NodeMcpGateway mcpGateway;
    private DispatchGate gate;
    private DispatchGate.Decision decision;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        mcpGateway = mock(NodeMcpGateway.class);
        when(mcpGateway.callTool(any(), any(), any(), any())).thenReturn(new NodeMcpGateway.ToolOutcome(
                null, Map.of("output", "ok"), Map.of("rendered", true), 3));
        gate = mock(DispatchGate.class);
        decision = mock(DispatchGate.Decision.class);
        when(decision.outcome()).thenReturn(DispatchGate.Decision.Outcome.ALLOWED);
        when(gate.evaluate(any())).thenReturn(decision);
    }

    @Test
    void allModelProfilesUnavailableDegradesToAReadOnlyPlaybook() {
        LeasedAgentRun run = newQueuedRun("磁盘占用过高，请分析并给出清理方案");

        worker().execute(run);

        Map<String, Object> row = jdbc.queryForMap(
                "select status, outcome, final_result_json, usage_json from agent_run where id = ?", run.id());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("outcome")).isEqualTo("SUCCEEDED");

        String finalResult = String.valueOf(row.get("final_result_json"));
        assertThat(finalResult).contains("\"degraded\":true")
                .contains("MODEL_UNAVAILABLE")
                .contains("disk-cleanup-diagnosis")
                .contains("LLM 不可用，已降级为确定性只读剧本");

        // 时间线非空，且降级事件在里面（任务详情据此显示"已降级"，不假装是模型输出）。
        List<String> eventTypes = store.eventsAfter(AgentTestDatabase.TENANT_ID, run.id(), 0, 100).stream()
                .map(AgentRunEvent::eventType).toList();
        assertThat(eventTypes).isNotEmpty()
                .contains(AgentRoundExecutor.EVENT_MODEL_UNAVAILABLE_FALLBACK)
                .contains("MODEL_INVOCATION_FAILED")
                .contains("RUN_COMPLETED");

        // 红线：兜底产生的每一次工具调用都必须是只读，且绝不走带令牌的写通道。
        ArgumentCaptor<NodeTool> executed = ArgumentCaptor.forClass(NodeTool.class);
        verify(mcpGateway, org.mockito.Mockito.atLeastOnce())
                .callTool(any(), executed.capture(), any(), any());
        assertThat(executed.getAllValues()).isNotEmpty()
                .allSatisfy(tool -> assertThat(tool.readOnly()).as("兜底只允许只读工具：%s", tool.toolId()).isTrue());
        assertThat(executed.getAllValues()).extracting(NodeTool::toolId).allMatch(DISK_CHAIN::contains);
        verify(mcpGateway, never()).callApprovedTool(any(), any(), any(), any());
        assertThat(String.valueOf(row.get("usage_json"))).doesNotContain("writeActions");
    }

    /** 兜底不是"绕过门禁的后门"：每一步照样过 DispatchGate，被拒的步骤记 SKIPPED 而非偷偷执行。 */
    @Test
    void fallbackStepsAreStillSubjectToTheDispatchGate() {
        when(decision.outcome()).thenReturn(DispatchGate.Decision.Outcome.REJECTED);
        when(decision.rejectionCode()).thenReturn("ABAC_DENIED_HOST");
        LeasedAgentRun run = newQueuedRun("磁盘占用过高，请分析并给出清理方案");

        worker().execute(run);

        verify(mcpGateway, never()).callTool(any(), any(), any(), any());
        String finalResult = jdbc.queryForObject(
                "select final_result_json from agent_run where id = ?", String.class, run.id());
        assertThat(finalResult).contains("SKIPPED").contains("ABAC_DENIED_HOST");
    }

    /** 匹配不到任何只读剧本 → 诚实失败，绝不瞎跑一条无关的链充数。 */
    @Test
    void objectiveWithoutAnyMatchingChainFailsHonestly() {
        LeasedAgentRun run = newQueuedRun("帮我给财务部同事写一封邮件");

        worker().execute(run);

        Map<String, Object> row = jdbc.queryForMap(
                "select status, outcome, final_result_json from agent_run where id = ?", run.id());
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("outcome")).isEqualTo("NOT_ACHIEVED");
        assertThat(String.valueOf(row.get("final_result_json"))).doesNotContain("degraded");
        assertThat(store.eventsAfter(AgentTestDatabase.TENANT_ID, run.id(), 0, 100).stream()
                .map(AgentRunEvent::eventType))
                .doesNotContain(AgentRoundExecutor.EVENT_MODEL_UNAVAILABLE_FALLBACK)
                .contains("RUN_FAILED");
        verify(mcpGateway, never()).callTool(any(), any(), any(), any());
    }

    private LeasedAgentRun newQueuedRun(String objective) {
        String runId = AgentTestFixtures.insertRun(jdbc, "QUEUED", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "QUEUED");
        jdbc.update("update agent_run set root_agent_instance_id = ?, objective = ? where id = ?",
                instanceId, objective, runId);
        return store.leaseSpecific(runId);
    }

    private AgentRunWorker worker() {
        SimpleModelRouter router = mock(SimpleModelRouter.class);
        RoutedModel routed = mock(RoutedModel.class);
        LlmModelConfig config = mock(LlmModelConfig.class);
        when(config.providerType()).thenReturn("test-provider");
        when(config.model()).thenReturn("stub-model");
        when(routed.config()).thenReturn(config);
        when(routed.configDigest()).thenReturn("cfg-digest");
        when(routed.reasoningLevel()).thenReturn("OFF");
        when(routed.reason()).thenReturn("test-route");
        // 空 profileId：走 legacy 单模型配置那条路由分支（agent_instance.model_profile_id 存 null）。
        when(routed.profileId()).thenReturn("");
        when(router.route(any(), any(), any(), any())).thenReturn(routed);
        // 备选 profile 也一个都没有 —— "全部模型 profile 不可用"成立。
        when(router.alternatives(any(), any(), any())).thenReturn(List.of());

        ModelGateway modelGateway = mock(ModelGateway.class);
        when(modelGateway.complete(any(), any()))
                .thenThrow(new AppException(HttpStatus.BAD_GATEWAY, "模型接口调用失败"));

        ToolRegistry registry = mock(ToolRegistry.class);
        for (String toolId : DISK_CHAIN) {
            NodeTool tool = mock(NodeTool.class);
            when(tool.toolId()).thenReturn(toolId);
            when(tool.readOnly()).thenReturn(true);
            when(tool.alwaysRequireApproval()).thenReturn(false);
            when(registry.lookup(toolId)).thenReturn(Optional.of(tool));
        }
        when(registry.allTools()).thenReturn(List.of());
        when(registry.catalogEntriesForGrant(any())).thenReturn(List.of());
        when(registry.catalogDigest()).thenReturn("catalog-digest");

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        return new AgentRunWorker(true, 5, "R2", store,
                new ContextCompiler(AgentTestFixtures.MAPPER, metrics),
                new ContextWindowPolicy(32_000, 0.6),
                new ContextSummarizer(AgentTestFixtures.MAPPER, metrics),
                router, modelGateway, mcpGateway, registry, gate,
                new ApprovalService(jdbc, store, mock(ResourceAccessService.class)),
                mock(RuntimeToolDispatcher.class), mock(ConnectorToolDispatcher.class),
                mock(SubAgentLifecycleService.class),
                new MemoryService(new MemoryStore(jdbc), AgentTestFixtures.MAPPER),
                new RunCheckpointService(jdbc, AgentTestFixtures.MAPPER), AgentTestFixtures.MAPPER);
    }
}

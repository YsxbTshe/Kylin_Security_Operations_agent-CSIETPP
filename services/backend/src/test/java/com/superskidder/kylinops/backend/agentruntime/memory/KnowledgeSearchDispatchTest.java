package com.superskidder.kylinops.backend.agentruntime.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.agent.HandoffService;
import com.superskidder.kylinops.backend.agentruntime.agent.RuntimeToolDispatcher;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.rag.RagChunkItem;
import com.superskidder.kylinops.backend.rag.RagSearchContext;
import com.superskidder.kylinops.backend.rag.RagService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** RuntimeToolDispatcher 对 knowledge.search 分支的分流测试（不触碰招募链路的依赖）。 */
class KnowledgeSearchDispatchTest {

    private static final String TENANT_ID = "org-alpha";

    private RuntimeToolDispatcher dispatcher;
    private KnowledgeSearchTool tool;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        tool = new KnowledgeSearchTool(ragService, new ObjectMapper());
        dispatcher = new RuntimeToolDispatcher(mock(AgentRuntimeStore.class), mock(HandoffService.class), new ObjectMapper(),
                org.mockito.Mockito.mock(com.superskidder.kylinops.backend.registry.AgentRegistryService.class));
    }

    private LeasedAgentRun run() {
        return new LeasedAgentRun("run-1", TENANT_ID, "user-alpha", "agent-root",
                "lookup runbook", "CAUTIOUS", "{\"nodeId\":\"node-1\"}", "{}", "{}", 0L, null, "OFF");
    }

    @Test
    void dispatchReturnsStructuredSnippetsScopedToTenant() {
        when(ragService.retrieveContext(any(), any(), any())).thenReturn(new RagSearchContext(
                List.of(new RagChunkItem("chk-1", "doc-1", 0, "Runbook", "TEXT", "重启前先看负载", 0.8D)),
                List.of(), List.of(), List.of()));

        RuntimeToolDispatcher.DispatchOutcome outcome =
                dispatcher.dispatch(run(), tool, Map.of("query", "重启流程"));

        assertThat(outcome.handoffId()).isNull();
        // 同步工具：不得把 run 挂起，否则执行循环会白等一次轮询/租约超时（约 60 秒静默）。
        assertThat(outcome.runSuspended()).isFalse();
        assertThat(outcome.structuredOutput().get("available")).isEqualTo(true);
        assertThat(outcome.structuredOutput().get("resultCount")).isEqualTo(1);
        assertThat(outcome.renderedOutput()).containsEntry("type", "knowledge-search");
    }

    @Test
    void invalidTopKSurfacesAsToolErrorWithoutCrashingTheLoop() {
        assertThatThrownBy(() -> dispatcher.dispatch(run(), tool, Map.of("query", "x", "topK", 99)))
                .isInstanceOf(NodeMcpGateway.ToolCallException.class)
                .extracting(e -> ((NodeMcpGateway.ToolCallException) e).errorCode())
                .isEqualTo("KNOWLEDGE_SEARCH_INVALID");
    }

    @Test
    void ragFailureDegradesToExplicitEmptyResult() {
        when(ragService.retrieveContext(any(), any(), any()))
                .thenThrow(new IllegalStateException("knowledge base offline"));

        RuntimeToolDispatcher.DispatchOutcome outcome =
                dispatcher.dispatch(run(), tool, Map.of("query", "重启流程"));

        assertThat(outcome.structuredOutput().get("available")).isEqualTo(false);
        assertThat(outcome.structuredOutput().get("resultCount")).isEqualTo(0);
        assertThat((List<?>) outcome.structuredOutput().get("results")).isEmpty();
    }
}

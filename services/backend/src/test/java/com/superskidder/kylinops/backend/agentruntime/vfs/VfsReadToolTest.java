package com.superskidder.kylinops.backend.agentruntime.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.agent.HandoffService;
import com.superskidder.kylinops.backend.agentruntime.agent.RuntimeToolDispatcher;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolExecutor;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * kylin.vfs.read 的工具层 + 分派层验收：参数边界、来源标注、未知 scheme fail-closed、
 * 路径穿越拒绝、跨 AgentInstance 拒绝、未连通的读取路径不得退化成空内容。
 */
class VfsReadToolTest {

    private static final String TENANT = "org-alpha";
    private static final String AGENT_INSTANCE = "agent-root";

    private VfsReadTool tool;
    private RuntimeToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        SkillVfsResolver skill = new SkillVfsResolver((skillId, relativePath) ->
                "kylin.ops.disk-triage".equals(skillId)
                        ? Optional.of(new SkillVfsResolver.SkillFile(
                                "# 磁盘排查\n先看 df -h".getBytes(StandardCharsets.UTF_8), "text/markdown"))
                        : Optional.empty());
        WorkspaceVfsResolver workspace = new WorkspaceVfsResolver(
                (agentInstanceId, relativePath) -> new ByteArrayInputStream(
                        "workspace-bytes".getBytes(StandardCharsets.UTF_8)));
        tool = new VfsReadTool(new VfsService(List.of(skill, workspace)), objectMapper);
        dispatcher = new RuntimeToolDispatcher(mock(AgentRuntimeStore.class), mock(HandoffService.class), objectMapper,
                org.mockito.Mockito.mock(com.superskidder.kylinops.backend.registry.AgentRegistryService.class));
    }

    private LeasedAgentRun run() {
        return new LeasedAgentRun("run-1", TENANT, "user-alpha", AGENT_INSTANCE,
                "读取 skill 正文", "CAUTIOUS", "{\"nodeId\":\"node-1\"}", "{}", "{}", 0L, null, "OFF");
    }

    @Test
    void declaresReadOnlyRuntimeSemantics() {
        assertThat(tool.toolId()).isEqualTo("kylin.vfs.read");
        assertThat(tool.executor()).isEqualTo(ToolExecutor.RUNTIME);
        assertThat(tool.readOnly()).isTrue();
        assertThat(tool.riskFloor()).isEqualTo("R0");
        assertThat(tool.alwaysRequireApproval()).isFalse();
        assertThat(tool.idempotency()).isEqualTo(ToolIdempotency.IDEMPOTENT);
        assertThat(tool.llmTool()).isNotNull();
        assertThat(tool.inputSchemaDigest()).matches("[0-9a-f]{64}");
        assertThat(tool.outputSchemaDigest()).matches("[0-9a-f]{64}");
    }

    @Test
    void validatesArguments() {
        assertThatThrownBy(() -> tool.validateArguments(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(Map.of("uri", "skill://a/b", "extra", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported field");
        assertThatThrownBy(() -> tool.validateArguments(Map.of("uri", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(Map.of("uri", "x".repeat(600))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(Map.of("uri", "/etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme://");
        assertThatThrownBy(() -> tool.validateArguments(Map.of("uri", 42)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsSkillResourceWithSourceAnnotation() {
        Map<String, Object> output = tool.read(TENANT, AGENT_INSTANCE,
                tool.validateArguments(Map.of("uri", "skill://kylin.ops.disk-triage/SKILL.md")));

        assertThat(output).containsEntry("source", "skill://kylin.ops.disk-triage/SKILL.md")
                .containsEntry("scheme", "skill")
                .containsEntry("resourceId", "kylin.ops.disk-triage")
                .containsEntry("path", "SKILL.md")
                .containsEntry("encoding", "utf-8")
                .containsEntry("truncated", false)
                .containsEntry("provenance", VfsReadTool.PROVENANCE);
        assertThat(String.valueOf(output.get("content"))).contains("df -h");
    }

    @Test
    void unknownSchemeFailsClosedInsteadOfReturningEmptyContent() {
        for (String uri : List.of("file://tmp/passwd", "http://example.com/x", "s3://bucket/key")) {
            assertThatThrownBy(() -> tool.read(TENANT, AGENT_INSTANCE,
                    tool.validateArguments(Map.of("uri", uri))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown vfs scheme");
        }
    }

    @Test
    void pathTraversalIsRejectedByPathSecurity() {
        for (String uri : List.of("skill://kylin.ops.disk-triage/../../etc/passwd",
                "workspace://agent-root/a/../../../etc/shadow",
                "skill://kylin.ops.disk-triage/a\\..\\b")) {
            assertThatThrownBy(() -> tool.read(TENANT, AGENT_INSTANCE,
                    tool.validateArguments(Map.of("uri", uri))))
                    .isInstanceOf(PathSecurity.VfsPathSecurityException.class);
        }
    }

    @Test
    void crossAgentWorkspaceReadIsDenied() {
        assertThatThrownBy(() -> tool.read(TENANT, AGENT_INSTANCE,
                tool.validateArguments(Map.of("uri", "workspace://agent-other/out.txt"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent instance mismatch");
    }

    @Test
    void unregisteredSkillIsRejected() {
        assertThatThrownBy(() -> tool.read(TENANT, AGENT_INSTANCE,
                tool.validateArguments(Map.of("uri", "skill://kylin.ops.unknown/SKILL.md"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill not registered");
    }

    @Test
    void unconnectedResolverSurfacesAsUnsupportedNotEmpty() {
        // workspace runner 未连通时的默认存根：必须报不支持，绝不能回一个"读到了空文件"。
        VfsReadTool stubbed = new VfsReadTool(
                new VfsService(List.of(new WorkspaceVfsResolver())), new ObjectMapper());

        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), stubbed,
                Map.of("uri", "workspace://" + AGENT_INSTANCE + "/out.txt"))))
                .isEqualTo("VFS_READ_UNSUPPORTED");
    }

    @Test
    void dispatchCompletesSynchronouslyAndRendersResult() {
        RuntimeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(run(), tool,
                Map.of("uri", "skill://kylin.ops.disk-triage/SKILL.md"));

        assertThat(outcome.runSuspended()).isFalse();
        assertThat(outcome.structuredOutput()).containsEntry("scheme", "skill");
        assertThat(outcome.renderedOutput()).containsEntry("type", "vfs-read");
    }

    @Test
    void dispatcherMapsFailuresToStableErrorCodes() {
        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), tool, Map.of("uri", "/etc/passwd"))))
                .isEqualTo("VFS_READ_INVALID");
        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), tool, Map.of("uri", "file://tmp/x"))))
                .isEqualTo("VFS_READ_REJECTED");
        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), tool,
                Map.of("uri", "skill://kylin.ops.disk-triage/../../etc/passwd"))))
                .isEqualTo("VFS_PATH_REJECTED");
        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), tool,
                Map.of("uri", "workspace://agent-other/out.txt"))))
                .isEqualTo("VFS_READ_REJECTED");
    }

    @Test
    void workspaceReadIsScopedToTheRunsOwnAgentInstance() {
        Map<String, Object> output = tool.read(TENANT, AGENT_INSTANCE,
                tool.validateArguments(Map.of("uri", "workspace://" + AGENT_INSTANCE + "/out.txt")));

        assertThat(output).containsEntry("scheme", "workspace")
                .containsEntry("content", "workspace-bytes");
    }

    private String errorCodeOf(Runnable call) {
        try {
            call.run();
        } catch (NodeMcpGateway.ToolCallException exception) {
            assertThat(exception.retryable()).isFalse();
            return exception.errorCode();
        }
        throw new AssertionError("expected a ToolCallException");
    }
}

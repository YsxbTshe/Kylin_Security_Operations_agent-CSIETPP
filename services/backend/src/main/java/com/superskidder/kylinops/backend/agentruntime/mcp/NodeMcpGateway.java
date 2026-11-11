package com.superskidder.kylinops.backend.agentruntime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.ApprovalService;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.persistence.ToolExecutionRef;
import com.superskidder.kylinops.backend.agentruntime.security.ApprovalTokenBinder;
import com.superskidder.kylinops.backend.agentruntime.security.ApprovalTokenLifecycle;
import com.superskidder.kylinops.backend.agentruntime.security.CanonicalJson;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import com.superskidder.kylinops.backend.agentruntime.tool.ServiceStatusTool;
import com.superskidder.kylinops.backend.agentruntime.transport.NodeDispatchRequest;
import com.superskidder.kylinops.backend.agentruntime.transport.NodeExecutorV2Registry;
import com.superskidder.kylinops.backend.agentruntime.transport.NodeInvocationResult;
import com.superskidder.kylinops.backend.crypto.Sm3;
import com.superskidder.kylinops.backend.policy.RiskCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NodeMcpGateway {

    private final AgentRuntimeStore store;
    private final NodeExecutorV2Registry registry;
    private final ServiceStatusTool serviceStatusTool;
    private final ApprovalTokenBinder tokenBinder;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public NodeMcpGateway(
            AgentRuntimeStore store,
            NodeExecutorV2Registry registry,
            ServiceStatusTool serviceStatusTool,
            ApprovalTokenBinder tokenBinder,
            ObjectMapper objectMapper,
            @Value("${app.agent-runtime.tool-timeout-seconds:30}") long timeoutSeconds
    ) {
        this.store = store;
        this.registry = registry;
        this.serviceStatusTool = serviceStatusTool;
        this.tokenBinder = tokenBinder;
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public ToolOutcome callServiceStatus(RuntimeToolContext context, Map<String, Object> arguments) {
        return callTool(context, serviceStatusTool, arguments, null);
    }

    public ToolOutcome callTool(RuntimeToolContext context, NodeTool tool, Map<String, Object> arguments,
                                String approvalToken) {
        long started = System.nanoTime();
        Map<String, Object> validated = tool.validateArguments(arguments);
        String canonicalArguments = canonical(validated);
        LeasedAgentRun run = context.run();
        List<NodeDispatchRequest.LockEntry> locks = resourceLocks(context, tool, validated);
        long lockVersion = store.getOrIncrementNodeLockVersion(run.tenantId(), context.nodeId());
        ToolExecutionRef execution = store.startToolInvocation(run, context.agentInvocationId(),
                context.nodeId(), tool, canonicalArguments, locks, lockVersion);
        return dispatchAndRender(context, tool, execution, canonicalArguments,
                tool.riskFloor(), lockVersion, locks, approvalToken, started);
    }

    /**
     * 经人工批准的调用：复用审批占位 invocation，认领唯一 attempt（一次性消费），
     * 并在 dispatch 前经 {@link ApprovalTokenBinder} 铸造绑定「租户/run/实例/调用/attempt/
     * 节点/工具/参数摘要/风险/模式/锁版本」的 SM2 令牌。令牌只在内存中存活到 dispatch，不落库。
     */
    public ToolOutcome callApprovedTool(RuntimeToolContext context, NodeTool tool,
                                        Map<String, Object> arguments, ApprovalService.ApprovalGrant grant) {
        long started = System.nanoTime();
        Map<String, Object> validated = tool.validateArguments(arguments);
        String canonicalArguments = canonical(validated);
        String argumentsDigest = Sm3.hex(canonicalArguments);
        if (!argumentsDigest.equals(grant.argumentsDigest())) {
            // 先于 attempt 认领检查：失配请求不得消费一次性审批（binder 铸造前会再复核一次）。
            throw new ToolCallException(ApprovalTokenBinder.APPROVAL_BINDING_MISMATCH,
                    "Arguments no longer match the approved request", false);
        }
        LeasedAgentRun run = context.run();
        List<NodeDispatchRequest.LockEntry> locks = resourceLocks(context, tool, validated);
        long lockVersion = store.getOrIncrementNodeLockVersion(run.tenantId(), context.nodeId());
        String attemptId = store.claimAttemptForApprovedInvocation(
                        grant.toolInvocationId(), run.tenantId(), context.nodeId(), lockVersion, argumentsDigest)
                .orElseThrow(() -> new ToolCallException("APPROVAL_ALREADY_CONSUMED",
                        "Approval was already consumed by another attempt", false));
        String riskCeiling = maxRisk(grant.riskCeiling(), tool.riskFloor());
        ApprovalTokenLifecycle.IssuedToken token;
        try {
            token = tokenBinder.bind(new ApprovalTokenBinder.BindRequest(
                    run.tenantId(), run.id(), run.rootAgentInstanceId(), context.agentInvocationId(),
                    grant.toolInvocationId(), attemptId, context.nodeId(), tool.toolId(), tool.version(),
                    canonicalArguments, grant.argumentsDigest(),
                    riskCeiling, run.executionMode(), lockVersion));
        } catch (ApprovalTokenBinder.BindingMismatchException exception) {
            // 参数与批准时的摘要失配即拒绝：批准的是什么就只能执行什么。
            throw new ToolCallException(ApprovalTokenBinder.APPROVAL_BINDING_MISMATCH,
                    exception.getMessage(), false);
        }
        ToolExecutionRef execution = new ToolExecutionRef(
                grant.toolInvocationId(), attemptId, canonicalArguments);
        return dispatchAndRender(context, tool, execution, canonicalArguments,
                riskCeiling, lockVersion, locks, token.encoded(), started);
    }

    private ToolOutcome dispatchAndRender(RuntimeToolContext context, NodeTool tool, ToolExecutionRef execution,
                                          String canonicalArguments, String riskCode, long lockVersion,
                                          List<NodeDispatchRequest.LockEntry> locks, String approvalToken,
                                          long startedNanos) {
        LeasedAgentRun run = context.run();
        if (!tool.readOnly()) {
            // 写预算计数（ADR-001 §2.9）：attempt 即将下发，无论成败都可能产生真实副作用。
            store.addWriteActionUsage(run.id());
        }
        NodeInvocationResult nodeResult = registry.dispatch(new NodeDispatchRequest(
                run.tenantId(), run.id(), run.rootAgentInstanceId(), context.agentInvocationId(),
                execution.toolInvocationId(), execution.executionAttemptId(), context.nodeId(),
                tool.toolId(), tool.version(), canonicalArguments,
                Sm3.hex(canonicalArguments),
                tool.inputSchemaDigest(), tool.outputSchemaDigest(),
                Sm3.hex(run.grantSnapshotJson()),
                tool.catalogDigest(),
                riskCode, run.executionMode(), tool.readOnly(), lockVersion,
                locks, approvalToken, timeout));
        if (!nodeResult.success()) {
            throw new ToolCallException(nodeResult.errorCode(), nodeResult.message(), nodeResult.retryable());
        }
        Map<String, Object> output = tool.validateOutput(nodeResult.structuredResultJson());
        Map<String, Object> rendered = tool.render(output);
        long overheadMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        return new ToolOutcome(execution, output, rendered, overheadMillis);
    }

    private List<NodeDispatchRequest.LockEntry> resourceLocks(RuntimeToolContext context, NodeTool tool,
                                                              Map<String, Object> validated) {
        List<NodeDispatchRequest.LockEntry> locks = tool.resourceLockTemplates().stream()
                .map(tpl -> new NodeDispatchRequest.LockEntry(
                        resolveResourcePattern(tpl.resourcePattern(), validated), tpl.lockMode()))
                .toList();
        if (locks.isEmpty() && !tool.readOnly()) {
            locks = List.of(new NodeDispatchRequest.LockEntry(
                    "node:" + context.nodeId() + ":tool:" + tool.toolId(), "WRITE_EXCLUSIVE"));
        }
        if (locks.isEmpty()) {
            locks = List.of(new NodeDispatchRequest.LockEntry(
                    "node:" + context.nodeId() + ":tool:" + tool.toolId(), "READ_SHARED"));
        }
        return locks;
    }

    private String maxRisk(String left, String right) {
        RiskCode a = RiskCode.normalize(left);
        RiskCode b = RiskCode.normalize(right);
        return (a.ordinal() >= b.ordinal() ? a : b).name();
    }

    private String resolveResourcePattern(String pattern, Map<String, Object> arguments) {
        String resolved = pattern;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return resolved;
    }

    private String canonical(Object value) {
        try {
            return CanonicalJson.canonicalize(objectMapper.writeValueAsString(value));
        } catch (Exception exception) {
            throw new IllegalArgumentException("tool arguments cannot be serialized", exception);
        }
    }

    public record ToolOutcome(
            ToolExecutionRef execution,
            Map<String, Object> structuredOutput,
            Map<String, Object> renderedOutput,
            long controlOverheadMillis
    ) {}

    public static class ToolCallException extends RuntimeException {
        private final String errorCode;
        private final boolean retryable;

        public ToolCallException(String errorCode, String message, boolean retryable) {
            super(message == null || message.isBlank() ? "Node tool call failed" : message);
            this.errorCode = errorCode == null || errorCode.isBlank() ? "NODE_TOOL_FAILED" : errorCode;
            this.retryable = retryable;
        }

        public String errorCode() { return errorCode; }
        public boolean retryable() { return retryable; }
    }
}

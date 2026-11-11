package com.superskidder.kylinops.backend.agentruntime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.agent.RuntimeTool;
import com.superskidder.kylinops.backend.agentruntime.agent.RuntimeToolDispatcher;
import com.superskidder.kylinops.backend.agentruntime.agent.SubAgentLifecycleService;
import com.superskidder.kylinops.backend.agentruntime.checkpoint.RunCheckpointService;
import com.superskidder.kylinops.backend.agentruntime.connector.ConnectorTool;
import com.superskidder.kylinops.backend.agentruntime.connector.ConnectorToolDispatcher;
import com.superskidder.kylinops.backend.agentruntime.context.CompiledContext;
import com.superskidder.kylinops.backend.agentruntime.context.ContextCompiler;
import com.superskidder.kylinops.backend.agentruntime.context.ContextSummarizer;
import com.superskidder.kylinops.backend.agentruntime.context.ContextWindowPolicy;
import com.superskidder.kylinops.backend.agentruntime.context.PromptSegment;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.mcp.RuntimeToolContext;
import com.superskidder.kylinops.backend.agentruntime.memory.MemoryService;
import com.superskidder.kylinops.backend.agentruntime.model.DeterministicFallbackPlaybook;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGateway;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGatewayRequest;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGatewayResult;
import com.superskidder.kylinops.backend.agentruntime.model.RoutedModel;
import com.superskidder.kylinops.backend.agentruntime.model.SimpleModelRouter;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.security.CanonicalJson;
import com.superskidder.kylinops.backend.agentruntime.security.DispatchGate;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import com.superskidder.kylinops.backend.agentruntime.tool.ToolRegistry;
import com.superskidder.kylinops.backend.crypto.Sm3;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 一轮或多轮 ReAct 循环：模型调用、DispatchGate、审批、工具执行、预算与停滞判定。
 * 从 {@link AgentRunWorker} 拆出，Worker 只负责租约轮询。
 */
public class AgentRoundExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentRoundExecutor.class);

    private final int maxAgenticRounds;
    private final long modelTimeoutSeconds;
    private final long nativeGuardMaxAgeMinutes;
    private final String autoRiskCeiling;
    private final AgentRuntimeStore store;
    private final ContextCompiler contextCompiler;
    private final ContextWindowPolicy windowPolicy;
    private final ContextSummarizer contextSummarizer;
    private final SimpleModelRouter modelRouter;
    private final ModelGateway modelGateway;
    private final NodeMcpGateway mcpGateway;
    private final ToolRegistry toolRegistry;
    private final DispatchGate dispatchGate;
    private final ApprovalService approvalService;
    private final RuntimeToolDispatcher runtimeToolDispatcher;
    private final ConnectorToolDispatcher connectorToolDispatcher;
    private final SubAgentLifecycleService subAgentLifecycle;
    private final MemoryService memoryService;
    private final RunCheckpointService checkpoints;
    private final ObjectMapper objectMapper;

    public AgentRoundExecutor(
            int maxAgenticRounds,
            long modelTimeoutSeconds,
            long nativeGuardMaxAgeMinutes,
            String autoRiskCeiling,
            AgentRuntimeStore store,
            ContextCompiler contextCompiler,
            ContextWindowPolicy windowPolicy,
            ContextSummarizer contextSummarizer,
            SimpleModelRouter modelRouter,
            ModelGateway modelGateway,
            NodeMcpGateway mcpGateway,
            ToolRegistry toolRegistry,
            DispatchGate dispatchGate,
            ApprovalService approvalService,
            RuntimeToolDispatcher runtimeToolDispatcher,
            ConnectorToolDispatcher connectorToolDispatcher,
            SubAgentLifecycleService subAgentLifecycle,
            MemoryService memoryService,
            RunCheckpointService checkpoints,
            ObjectMapper objectMapper
    ) {
        this.maxAgenticRounds = Math.max(1, maxAgenticRounds);
        this.modelTimeoutSeconds = Math.max(5, modelTimeoutSeconds);
        this.nativeGuardMaxAgeMinutes = Math.max(1, nativeGuardMaxAgeMinutes);
        this.autoRiskCeiling = autoRiskCeiling == null || autoRiskCeiling.isBlank() ? "R2" : autoRiskCeiling.trim();
        this.store = store;
        this.contextCompiler = contextCompiler;
        this.windowPolicy = windowPolicy;
        this.contextSummarizer = contextSummarizer;
        this.modelRouter = modelRouter;
        this.modelGateway = modelGateway;
        this.mcpGateway = mcpGateway;
        this.toolRegistry = toolRegistry;
        this.dispatchGate = dispatchGate;
        this.approvalService = approvalService;
        this.runtimeToolDispatcher = runtimeToolDispatcher;
        this.connectorToolDispatcher = connectorToolDispatcher;
        this.subAgentLifecycle = subAgentLifecycle;
        this.memoryService = memoryService;
        this.checkpoints = checkpoints;
        this.objectMapper = objectMapper;
    }

    public void execute(LeasedAgentRun run) {
        try {
            Map<String, Object> target = readMap(run.targetScopeJson());
            Map<String, Object> grant = readMap(run.grantSnapshotJson());
            validateBudget(run.budgetJson());
            String nodeId = required(target, "nodeId");

            List<Map<String, Object>> grantTools = toolRegistry.catalogEntriesForGrant(grant);
            List<LlmTool> llmTools = toolRegistry.allTools().stream()
                    .filter(t -> grantAllows(grant, t))
                    .map(NodeTool::llmTool)
                    .filter(t -> t != null)
                    .toList();

            CompiledContext context = contextCompiler.compile(new ContextCompiler.CompileRequest(
                    run.objective(), run.executionMode(), target, grant,
                    ContextCompiler.ROOT_TASK_AGENT_INSTRUCTIONS,
                    grantTools, toolRegistry.catalogDigest()));
            String contextFrameId = store.saveContext(run, context);
            RoutedModel model = modelRouter.route(run.tenantId(), run.objective(),
                    run.modelProfileId(), run.reasoningLevel());

            HistoryState history = restoreHistory(run);
            int startRound = restoreStartRound(run);
            Map<String, Object> lastRenderedOutput = null;
            long totalOverheadMillis = 0;

            // 预算五元组执行（ADR-001 §2.9）：轮次/工具调用为跨恢复的持久计数（usage_json），
            // 墙钟为本执行会话内计时（等待审批/子 Agent 的挂起时间天然不计入），
            // 停滞用证据指纹（工具+参数+结果摘要）判定，写次数在 dispatch 收口处累加。
            BudgetLimits limits = readBudgetLimits(run.budgetJson());
            long sessionStartNanos = System.nanoTime();
            Set<String> seenFingerprints = new HashSet<>();
            int consecutiveStagnantRounds = 0;

            // 审批唤醒/崩溃恢复：上一轮悬而未决的 tool_call（等待审批时不落占位结果）
            // 依据人工决定确定性地补齐——批准即带令牌执行「批准的那一次调用」，
            // 拒绝即回填拒绝结果交模型改道；不依赖模型复述工具调用。
            if (resolvePendingApprovals(run, nodeId, history, limits)) {
                store.transitionToWaiting(run, "AWAITING_HUMAN_APPROVAL");
                return;
            }

            // 节点心跳上报的真实 Native Guard 姿态（此前为硬编码 "AVAILABLE"，freshness 门
            // 形同虚设）。从未上报 → status=null → DispatchGate 对 R3+ 按 UNKNOWN 拒绝。
            AgentRuntimeStore.NodeGuardPosture guardPosture =
                    store.findNodeGuardPosture(run.tenantId(), nodeId).orElse(null);

            // 发起人角色（agent-tool ABAC 判据）：worker 异步执行、会话已不在，按发起人实时解析一次。
            // 此前恒传空角色 → DispatchGate 的 agent-tool ABAC 只会命中 USER 策略，种子无此类策略即
            // 对每次工具下发（含只读）fail-closed 拒绝，整条链路零通过。
            List<String> initiatorRoles = store.resolveRoleCodes(run.humanPrincipalId());

            for (int round = startRound; round < maxAgenticRounds; round++) {
                // 本实例的专家注册表身份（V10 的 agent_instance.registry_agent_key）。
                // 逐轮读而不是 execute 入口读一次：招募方在子 run 建好之后才写入这一列，
                // 逐轮读保证「绑定稍晚落地」也不会让子 Agent 的首轮逃过封套判定。
                // 根 Agent / 存量 run 恒为 null —— DispatchGate 此时不做注册表判定。
                String registryAgentKey = store.findRegistryAgentKey(run.rootAgentInstanceId()).orElse(null);
                String budgetStop = budgetStopReason(limits, store.readUsage(run.id()), sessionStartNanos);
                if (budgetStop != null) {
                    stopRun(run, "BUDGET_EXCEEDED", budgetStop);
                    return;
                }
                recordCheckpoint(run, round);
                store.renewLease(run.id(), Duration.ofSeconds(60));
                applyWindowBudget(run, context, history);
                ModelAttempt attempt = invokeModelWithFailover(run, contextFrameId, model,
                        new ModelGatewayRequest(context, llmTools, model.reasoningLevel(),
                                Duration.ofSeconds(modelTimeoutSeconds), renderExtraMessages(history)));
                if (attempt.unavailable()) {
                    // 全部模型 profile 均不可用（重试已耗尽）：降级为确定性只读剧本。
                    // 匹配不到剧本时 runDeterministicFallback 返回 false —— 按原样失败，
                    // 不假装还能工作（诚实优先于可用性表演）。
                    if (runDeterministicFallback(run, nodeId, target, initiatorRoles, grant,
                            guardPosture, registryAgentKey, attempt.failureMessage())) {
                        return;
                    }
                    throw attempt.failure();
                }
                String roundModelInvocationId = attempt.invocationId();
                ModelGatewayResult modelResult = attempt.result();

                if (modelResult.toolCalls().isEmpty()) {
                    if (lastRenderedOutput == null) {
                        lastRenderedOutput = Map.of("content", modelResult.content());
                    }
                    break;
                }

                List<Map<String, Object>> assistantToolCallMessages = new ArrayList<>();
                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", modelResult.content());
                List<Map<String, Object>> toolCallSpecs = new ArrayList<>();
                for (var call : modelResult.toolCalls()) {
                    toolCallSpecs.add(Map.of("id", call.id(), "type", "function",
                            "function", Map.of("name", call.name(), "arguments", call.argumentsJson())));
                }
                assistantMsg.put("tool_calls", toolCallSpecs);
                assistantToolCallMessages.add(assistantMsg);

                List<Map<String, Object>> toolResultMessages = new ArrayList<>();
                boolean needsApproval = false;
                boolean subAgentWaiting = false;
                String writeBudgetStop = null;

                for (var toolCall : modelResult.toolCalls()) {
                    NodeTool tool = toolRegistry.lookup(toolCall.name()).orElse(null);
                    if (tool == null) {
                        toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                Map.of("error", "unknown tool: " + toolCall.name())));
                        continue;
                    }

                    Map<String, Object> arguments = readMap(toolCall.argumentsJson());
                    Optional<Map<String, Object>> replayed = replayGuard(run, tool, arguments);
                    if (replayed.isPresent()) {
                        toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(), replayed.get()));
                        continue;
                    }
                    DispatchGate.Decision decision = dispatchGate.evaluate(new DispatchGate.DispatchRequest(
                            run.tenantId(), run.id(), run.humanPrincipalId(), initiatorRoles,
                            run.objective(), nodeId, tool.toolId(), toolCall.argumentsJson(),
                            tool.readOnly(), tool.alwaysRequireApproval(), tool.riskFloor(),
                            run.executionMode(),
                            grantToolIds(grant), grantReadOnly(grant), grantNodeIds(grant),
                            guardPosture != null ? guardPosture.status() : null,
                            guardPosture != null ? guardPosture.observedAt() : null,
                            nativeGuardMaxAgeMinutes, autoRiskCeiling, registryAgentKey));

                    switch (decision.outcome()) {
                        case ALLOWED -> {
                            if (!tool.readOnly() && writeBudgetExhausted(run, limits)) {
                                // 写预算触顶（ADR-001 §2.9 第五类）：本条写不执行，本轮结束后
                                // run 以 WRITE_BUDGET_EXCEEDED 终止，续跑需人工重新授予预算。
                                writeBudgetStop = "write action budget exhausted before tool " + tool.toolId();
                                toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                        Map.of("error", "write_budget_exceeded")));
                            } else if (tool instanceof RuntimeTool) {
                                try {
                                    store.renewLease(run.id(), Duration.ofSeconds(60));
                                    if (!tool.readOnly()) {
                                        store.addWriteActionUsage(run.id());
                                    }
                                    RuntimeToolDispatcher.DispatchOutcome outcome =
                                            runtimeToolDispatcher.dispatch(run, tool, arguments);
                                    bindRecruitedAgentToRegistry(run, outcome, arguments);
                                    lastRenderedOutput = outcome.renderedOutput();
                                    toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                            outcome.structuredOutput()));
                                    recordToolExecution(run, tool, true, "SUCCEEDED");
                                    // 只有真正把 run 挂起的 RUNTIME 工具（子 Agent 派生等）才结束本轮；
                                    // 同步返回结果的（知识检索等）必须继续下一轮，否则 run 既不在
                                    // WAITING 也不推进，只能等下一次轮询/租约超时才被捡回来。
                                    subAgentWaiting = subAgentWaiting || outcome.runSuspended();
                                } catch (NodeMcpGateway.ToolCallException toolException) {
                                    toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                            Map.of("error", toolException.getMessage(),
                                                    "error_code", toolException.errorCode(),
                                                    "retryable", toolException.retryable())));
                                    recordToolExecution(run, tool, false, toolException.getMessage());
                                }
                            } else if (tool instanceof ConnectorTool) {
                                try {
                                    store.renewLease(run.id(), Duration.ofSeconds(60));
                                    if (!tool.readOnly()) {
                                        store.addWriteActionUsage(run.id());
                                    }
                                    ConnectorToolDispatcher.DispatchOutcome outcome =
                                            connectorToolDispatcher.dispatch(run, tool, arguments);
                                    lastRenderedOutput = outcome.renderedOutput();
                                    toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                            outcome.structuredOutput()));
                                    recordToolExecution(run, tool, true, "SUCCEEDED");
                                } catch (NodeMcpGateway.ToolCallException toolException) {
                                    toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                            Map.of("error", toolException.getMessage(),
                                                    "error_code", toolException.errorCode(),
                                                    "retryable", toolException.retryable())));
                                    recordToolExecution(run, tool, false, toolException.getMessage());
                                }
                            } else {
                                try {
                                    store.renewLease(run.id(), Duration.ofSeconds(60));
                                    String currentInvocationId = store.startModelInvocation(run, contextFrameId, model);
                                    RuntimeToolContext toolContext =
                                            new RuntimeToolContext(run, currentInvocationId, nodeId);
                                    NodeMcpGateway.ToolOutcome outcome;
                                    if (decision.autoApproved() && !tool.readOnly()) {
                                        // AUTO 模式的写操作：自动批准也要有审计载体与令牌——
                                        // 端侧只认平台签名令牌，绝不因「策略说可以」而放行无令牌写。
                                        ApprovalService.ApprovalGrant autoGrant = autoApprove(
                                                run, nodeId, tool, arguments, decision, currentInvocationId);
                                        outcome = mcpGateway.callApprovedTool(toolContext, tool, arguments, autoGrant);
                                    } else {
                                        outcome = mcpGateway.callTool(toolContext, tool, arguments, null);
                                    }
                                    store.completeModelInvocation(run, currentInvocationId,
                                            new ModelGatewayResult("", List.of(), 0, 0, 0, 0, 0, 0, 0));
                                    lastRenderedOutput = outcome.renderedOutput();
                                    totalOverheadMillis += outcome.controlOverheadMillis();
                                    toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                            outcome.structuredOutput()));
                                    recordToolExecution(run, tool, true, "SUCCEEDED");
                                } catch (NodeMcpGateway.ToolCallException toolException) {
                                    toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                            Map.of("error", toolException.getMessage(),
                                                    "error_code", toolException.errorCode(),
                                                    "retryable", toolException.retryable())));
                                    recordToolExecution(run, tool, false, toolException.getMessage());
                                }
                            }
                        }
                        case NEEDS_APPROVAL -> {
                            if ("PLAN".equalsIgnoreCase(run.executionMode())) {
                                // approval 表约束 execution_mode ∈ {CAUTIOUS, AUTO}；PLAN 下的
                                // 需审批动作没有审批通道，直接拒绝（计划模式只读，无例外）。
                                toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                        Map.of("error", "approval_unsupported_in_plan_mode")));
                            } else {
                                String argumentsDigest = bindingDigest(tool, arguments);
                                Optional<ApprovalService.ApprovalGrant> existing = approvalService
                                        .findLatestByBinding(run.tenantId(), run.id(), nodeId,
                                                tool.toolId(), argumentsDigest);
                                Instant now = Instant.now();
                                if (existing.isPresent() && existing.get().actionable(now)) {
                                    // 决策已在两轮之间完成：立即带令牌执行，不再空转一轮。
                                    toolResultMessages.add(executeApproved(run, nodeId, tool, arguments,
                                            existing.get(),
                                            new PendingToolCall(toolCall.id(), toolCall.name(),
                                                    toolCall.argumentsJson())));
                                } else if (existing.isPresent() && existing.get().rejected()) {
                                    store.markInvocationRejected(existing.get().toolInvocationId(),
                                            "APPROVAL_REJECTED");
                                    toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                            Map.of("error", "approval_rejected",
                                                    "message", "A human approver rejected this action; "
                                                            + "do not retry with the same arguments")));
                                } else if (existing.isPresent() && existing.get().pending()) {
                                    // 同一绑定已有待审单，不重复建单。
                                    needsApproval = true;
                                } else {
                                    if (existing.isPresent() && existing.get().approvedButExpired(now)) {
                                        approvalService.markExpired(run.tenantId(), run.id(),
                                                existing.get().id());
                                    }
                                    String reuseInvocationId = existing
                                            .filter(existingGrant -> !existingGrant.consumed())
                                            .map(ApprovalService.ApprovalGrant::toolInvocationId)
                                            .orElse(null);
                                    requestApproval(run, nodeId, tool, arguments, reuseInvocationId,
                                            decision.riskCode() != null ? decision.riskCode().name() : "R3",
                                            roundModelInvocationId);
                                    needsApproval = true;
                                }
                            }
                        }
                        case REJECTED -> {
                            toolResultMessages.add(toolResultMessage(toolCall.id(), toolCall.name(),
                                    Map.of("error", "rejected",
                                            "code", decision.rejectionCode(),
                                            "message", decision.rejectionReason())));
                        }
                    }
                }

                for (Map<String, Object> message : assistantToolCallMessages) {
                    history.messages.add(store.appendRunMessage(run, history.nextSequence++, message));
                }
                for (Map<String, Object> message : toolResultMessages) {
                    history.messages.add(store.appendRunMessage(run, history.nextSequence++, message));
                }

                if (writeBudgetStop != null) {
                    stopRun(run, "WRITE_BUDGET_EXCEEDED", writeBudgetStop);
                    return;
                }
                if (needsApproval) {
                    store.transitionToWaiting(run, "AWAITING_HUMAN_APPROVAL");
                    // 竞态兜底：若审批在「建单 → 转 WAITING」窗口内已被决定，
                    // wakeupRun 的 WAITING 前置会漏掉这次唤醒，这里自唤醒一次。
                    if (anyPendingBindingDecided(run, nodeId, history)) {
                        store.wakeupRun(run.id());
                    }
                    return;
                }
                if (subAgentWaiting) {
                    return;
                }

                // 停滞判据（ADR-001 §2.9.2）：本轮全部证据指纹（工具+参数+结果摘要）
                // 均已在此前轮次出现过即为停滞轮；连续触顶即终止。等待轮不参与计数
                // （needsApproval/subAgentWaiting 已提前返回）。
                List<String> roundFingerprints = roundFingerprints(modelResult, toolResultMessages);
                boolean stagnantRound = !roundFingerprints.isEmpty()
                        && seenFingerprints.containsAll(roundFingerprints);
                consecutiveStagnantRounds = stagnantRound ? consecutiveStagnantRounds + 1 : 0;
                seenFingerprints.addAll(roundFingerprints);
                if (consecutiveStagnantRounds >= limits.maxStagnantRounds()) {
                    stopRun(run, "STAGNATED", "consecutive rounds produced no new evidence: "
                            + consecutiveStagnantRounds);
                    return;
                }
            }

            store.completeRun(run, lastRenderedOutput != null ? lastRenderedOutput : Map.of("content", "done"),
                    totalOverheadMillis);
            recordRunCompleted(run);
            notifySubAgentTerminal(run, true, null, null);
        } catch (Exception exception) {
            // 模型调用自身的失败已在 invokeModelWithFailover 内逐次落 FAILED（含 failover 的每一次尝试），
            // 这里只负责把 run 收成终态。
            try {
                store.failRun(run, errorCode(exception), exception.getMessage());
                notifySubAgentTerminal(run, false, errorCode(exception), exception.getMessage());
            } catch (RuntimeException terminalFailure) {
                log.error("AgentRun {} could not be marked failed", run.id(), terminalFailure);
            }
        }
    }

    // --- 模型可用性与确定性兜底 ---

    /** 降级审计事件类型：任务详情与时间线据此显示"LLM 不可用，已降级为确定性只读剧本"。 */
    public static final String EVENT_MODEL_UNAVAILABLE_FALLBACK = "MODEL_UNAVAILABLE_FALLBACK";

    /** 子 Agent 绑定注册表专家身份：此后它的每次工具调用都要过该专家的能力封套。 */
    public static final String EVENT_SUB_AGENT_REGISTRY_BOUND = "SUB_AGENT_REGISTRY_BOUND";

    /** 一轮模型调用的结果；{@code result == null} 表示所有候选 profile 都不可用。 */
    private record ModelAttempt(String invocationId, ModelGatewayResult result, RuntimeException failure) {

        boolean unavailable() {
            return result == null;
        }

        String failureMessage() {
            return failure == null ? "no model profile is available" : String.valueOf(failure.getMessage());
        }
    }

    /**
     * 模型调用（带 profile 级 failover）。主 profile 失败后按优先级依次尝试其余启用的 profile，
     * 每次尝试都有独立的 agent_invocation 记录（成功 SUCCEEDED / 失败 FAILED），
     * 全部失败才回报不可用——"所有模型 profile 均不可用"必须是验证过的结论。
     */
    private ModelAttempt invokeModelWithFailover(LeasedAgentRun run, String contextFrameId,
                                                 RoutedModel primary, ModelGatewayRequest request) {
        List<RoutedModel> candidates = new ArrayList<>();
        candidates.add(primary);
        try {
            List<RoutedModel> alternatives =
                    modelRouter.alternatives(run.tenantId(), primary.profileId(), run.reasoningLevel());
            if (alternatives != null) {
                candidates.addAll(alternatives);
            }
        } catch (RuntimeException routingFailure) {
            log.warn("AgentRun {} could not enumerate failover model profiles: {}",
                    run.id(), routingFailure.getMessage());
        }

        RuntimeException lastFailure = null;
        for (RoutedModel candidate : candidates) {
            String invocationId = store.startModelInvocation(run, contextFrameId, candidate);
            try {
                ModelGatewayResult result = modelGateway.complete(candidate, request);
                store.completeModelInvocation(run, invocationId, result);
                return new ModelAttempt(invocationId, result, null);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                log.warn("AgentRun {} model profile '{}' is unavailable: {}",
                        run.id(), candidate.profileId(), failure.getMessage());
                try {
                    store.failModelInvocation(run, invocationId, "MODEL_UNAVAILABLE", failure.getMessage());
                } catch (RuntimeException ignored) {
                    // 调用记录可能已终态；run 的终态由上层负责。
                }
            }
        }
        return new ModelAttempt(null, null,
                lastFailure != null ? lastFailure : new IllegalStateException("no model profile is available"));
    }

    /**
     * 确定性只读兜底（README 承诺的"LLM planner 可回退确定性规划"的最小兑现）。
     *
     * <p>按 objective 关键词选一条固定的只读工具链顺序执行，产出正常的任务时间线与审计事件。
     * <b>永远不触发写操作</b>：剧本本身只声明只读工具，这里还按工具目录的 readOnly 位再筛一次，
     * 并照常过 DispatchGate（ABAC/grant/风险门禁一个都不少）。
     *
     * <p>降级必须显式可见，不得伪装成模型输出：写入 {@value #EVENT_MODEL_UNAVAILABLE_FALLBACK}
     * 审计事件，且最终结果带 {@code degraded=true} 与人话说明。
     *
     * @return true 表示已按兜底剧本把 run 收成终态；false 表示没有可用剧本，调用方应照常失败
     */
    private boolean runDeterministicFallback(LeasedAgentRun run, String nodeId, Map<String, Object> target,
                                             List<String> initiatorRoles, Map<String, Object> grant,
                                             AgentRuntimeStore.NodeGuardPosture guardPosture,
                                             String registryAgentKey,
                                             String modelFailureMessage) {
        String service = target.get("service") == null ? null : String.valueOf(target.get("service"));
        Optional<DeterministicFallbackPlaybook.Chain> matched =
                DeterministicFallbackPlaybook.match(run.objective(), service);
        if (matched.isEmpty()) {
            log.warn("AgentRun {} has no deterministic fallback chain for its objective; failing honestly", run.id());
            return false;
        }
        DeterministicFallbackPlaybook.Chain chain = matched.get();

        // 只读硬约束的执行侧兜底：目录里查不到、不是只读、或 grant 不允许的步骤一律不执行。
        record ExecutableStep(NodeTool tool, Map<String, Object> arguments) {}
        List<ExecutableStep> executable = new ArrayList<>();
        for (DeterministicFallbackPlaybook.Step step : chain.steps()) {
            NodeTool tool = toolRegistry.lookup(step.toolId()).orElse(null);
            if (tool == null || !tool.readOnly() || !grantAllows(grant, tool)) {
                continue;
            }
            executable.add(new ExecutableStep(tool, step.arguments()));
        }
        if (executable.isEmpty()) {
            log.warn("AgentRun {} matched fallback chain {} but no read-only tool is executable", run.id(), chain.id());
            return false;
        }

        store.appendEvent(run.id(), run.tenantId(), EVENT_MODEL_UNAVAILABLE_FALLBACK, "RUNTIME", "deterministic",
                Map.of("reason", "ALL_MODEL_PROFILES_UNAVAILABLE",
                        "modelError", modelFailureMessage == null ? "" : modelFailureMessage,
                        "chainId", chain.id(),
                        "chainTitle", chain.title(),
                        "tools", executable.stream().map(step -> step.tool().toolId()).toList(),
                        "readOnly", true,
                        "degraded", true,
                        "message", "LLM 不可用，已降级为确定性只读剧本（结果非模型输出）"));

        List<Map<String, Object>> stepResults = new ArrayList<>();
        long overheadMillis = 0;
        for (ExecutableStep step : executable) {
            NodeTool tool = step.tool();
            DispatchGate.Decision decision = dispatchGate.evaluate(new DispatchGate.DispatchRequest(
                    run.tenantId(), run.id(), run.humanPrincipalId(), initiatorRoles,
                    run.objective(), nodeId, tool.toolId(), jsonString(step.arguments()),
                    /* readOnly */ true, tool.alwaysRequireApproval(), tool.riskFloor(),
                    run.executionMode(),
                    grantToolIds(grant), grantReadOnly(grant), grantNodeIds(grant),
                    guardPosture != null ? guardPosture.status() : null,
                    guardPosture != null ? guardPosture.observedAt() : null,
                    nativeGuardMaxAgeMinutes, autoRiskCeiling, registryAgentKey));
            if (decision.outcome() != DispatchGate.Decision.Outcome.ALLOWED) {
                stepResults.add(Map.of("toolId", tool.toolId(), "status", "SKIPPED",
                        "reason", decision.rejectionCode() == null
                                ? decision.outcome().name() : decision.rejectionCode()));
                continue;
            }
            try {
                store.renewLease(run.id(), Duration.ofSeconds(60));
                // agentInvocationId 留空：这些调用没有模型来源，谎称有反而污染因果链。
                NodeMcpGateway.ToolOutcome outcome = mcpGateway.callTool(
                        new RuntimeToolContext(run, null, nodeId), tool, step.arguments(), null);
                overheadMillis += outcome.controlOverheadMillis();
                stepResults.add(Map.of("toolId", tool.toolId(), "status", "SUCCEEDED",
                        "result", outcome.renderedOutput() == null ? Map.of() : outcome.renderedOutput()));
                recordToolExecution(run, tool, true, "SUCCEEDED");
            } catch (NodeMcpGateway.ToolCallException toolException) {
                stepResults.add(Map.of("toolId", tool.toolId(), "status", "FAILED",
                        "errorCode", String.valueOf(toolException.errorCode()),
                        "message", String.valueOf(toolException.getMessage())));
                recordToolExecution(run, tool, false, toolException.getMessage());
            }
        }

        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("degraded", true);
        rendered.put("degradedReason", "MODEL_UNAVAILABLE");
        rendered.put("fallbackChainId", chain.id());
        rendered.put("fallbackChainTitle", chain.title());
        rendered.put("content", "LLM 不可用，已降级为确定性只读剧本：" + chain.title()
                + "。以下结果由固定只读工具链直接采集，不是模型输出，也未执行任何写操作。");
        rendered.put("steps", stepResults);
        store.completeRun(run, rendered, overheadMillis);
        recordRunCompleted(run);
        notifySubAgentTerminal(run, true, null, null);
        return true;
    }

    /**
     * 招募即定身份：把 {@code recruit_agent} 声明的 {@code agent_key} 绑到新建的子实例上。
     *
     * <p>绑定后，子 Agent 的每一次工具调用都会在 DispatchGate 里多过一道注册表能力封套判定
     * （工具集 + 风险上限 + 启停，fail-closed）。未声明 {@code agent_key} 时不写，子 Agent
     * 与接入前一样只受 grant/ABAC/风险门禁约束。
     *
     * <p>声明了一个未注册/已停用的 key 不会被这里拦下——它同样会被写入，结果是该子 Agent
     * 的每一次工具调用都被 {@code AGENT_ENVELOPE_DENIED} 拒绝（authorize 对未注册即拒）。
     * 这是刻意的：伪造身份只会把自己关死，不会换来更宽的权限。
     *
     * <p>绑定失败（并发已绑定、实例不存在）只记日志不中断招募：子 Agent 此时没有身份，
     * 退化为接入前的行为，不会因为一次写入抖动把整条链路打断。
     */
    private void bindRecruitedAgentToRegistry(LeasedAgentRun run,
                                              RuntimeToolDispatcher.DispatchOutcome outcome,
                                              Map<String, Object> arguments) {
        if (outcome == null || outcome.agentInstanceId() == null || arguments == null) {
            return;
        }
        Object declared = arguments.get("agent_key");
        if (!(declared instanceof String agentKey) || agentKey.isBlank()) {
            return;
        }
        try {
            if (store.bindRegistryAgentKey(outcome.agentInstanceId(), agentKey)) {
                store.appendEvent(run.id(), run.tenantId(), EVENT_SUB_AGENT_REGISTRY_BOUND,
                        "RUNTIME", run.id(),
                        Map.of("childInstanceId", outcome.agentInstanceId(),
                                "childRunId", outcome.childRunId() == null ? "" : outcome.childRunId(),
                                "registryAgentKey", agentKey.trim()));
            }
        } catch (RuntimeException exception) {
            log.error("failed to bind registry agent key '{}' to instance {}",
                    agentKey, outcome.agentInstanceId(), exception);
        }
    }

    // --- 预算五元组（ADR-001 §2.9） ---

    private record BudgetLimits(long maxModelInvocations, long maxToolInvocations,
                                long maxWallClockSeconds, long maxWriteActions, long maxStagnantRounds) {}

    /** 五类预算限额；写次数与停滞轮数未显式声明时用保守默认（写 10 次 / 停滞 3 轮）。 */
    private BudgetLimits readBudgetLimits(String budgetJson) {
        Map<String, Object> budget = readMap(budgetJson);
        return new BudgetLimits(
                budgetLong(budget, "maxModelInvocations", Long.MAX_VALUE),
                budgetLong(budget, "maxToolInvocations", Long.MAX_VALUE),
                budgetLong(budget, "maxWallClockSeconds", Long.MAX_VALUE),
                budgetLong(budget, "maxWriteActions", 10),
                budgetLong(budget, "maxStagnantRounds", 3));
    }

    /** 轮首检查：模型调用数 / 工具调用数（跨恢复持久）与本会话墙钟任一触顶即停止。 */
    private String budgetStopReason(BudgetLimits limits, Map<String, Object> usage, long sessionStartNanos) {
        long models = usageLong(usage, "modelInvocations");
        if (models >= limits.maxModelInvocations()) {
            return "model invocation budget exhausted: " + models + "/" + limits.maxModelInvocations();
        }
        long tools = usageLong(usage, "toolInvocations");
        if (tools >= limits.maxToolInvocations()) {
            return "tool invocation budget exhausted: " + tools + "/" + limits.maxToolInvocations();
        }
        long elapsedSeconds = (System.nanoTime() - sessionStartNanos) / 1_000_000_000L;
        if (elapsedSeconds >= limits.maxWallClockSeconds()) {
            return "wall clock budget exhausted: " + elapsedSeconds + "s/" + limits.maxWallClockSeconds() + "s";
        }
        return null;
    }

    private boolean writeBudgetExhausted(LeasedAgentRun run, BudgetLimits limits) {
        return usageLong(store.readUsage(run.id()), "writeActions") >= limits.maxWriteActions();
    }

    /** 证据指纹（工具名 + 模型参数 + 结果内容的 SM3）；等待型结果同样参与——反复重试同一失败也是停滞。 */
    private List<String> roundFingerprints(ModelGatewayResult modelResult,
                                           List<Map<String, Object>> toolResultMessages) {
        Map<String, String> argumentsByCallId = new LinkedHashMap<>();
        for (var call : modelResult.toolCalls()) {
            argumentsByCallId.put(call.id(), call.argumentsJson());
        }
        List<String> fingerprints = new ArrayList<>();
        for (Map<String, Object> message : toolResultMessages) {
            String callId = String.valueOf(message.get("tool_call_id"));
            fingerprints.add(Sm3.hex(String.valueOf(message.get("name")) + "\u0000"
                    + argumentsByCallId.getOrDefault(callId, "") + "\u0000"
                    + String.valueOf(message.get("content"))));
        }
        return fingerprints;
    }

    /** 预算/停滞终止：终态 + 停止原因写入审计事件（RUN_FAILED.errorCode 即 stopReason）。 */
    private void stopRun(LeasedAgentRun run, String code, String reason) {
        log.warn("AgentRun {} stopped: {} ({})", run.id(), code, reason);
        store.failRun(run, code, reason);
        notifySubAgentTerminal(run, false, code, reason);
    }

    private long budgetLong(Map<String, Object> budget, String key, long fallback) {
        return budget.get(key) instanceof Number number && number.longValue() > 0
                ? number.longValue() : fallback;
    }

    private long usageLong(Map<String, Object> usage, String key) {
        return usage.get(key) instanceof Number number ? number.longValue() : 0L;
    }

    // --- 审批闭环（请求 → 人工决定 → 确定性恢复执行） ---

    /**
     * 恢复路径：补齐历史里没有结果的 tool_call。返回 true 表示仍有绑定在等人工决定，
     * run 应回到 WAITING/APPROVAL；返回 false 表示全部落定（已执行/拒绝/失败回填），
     * 可以继续模型循环。
     */
    private boolean resolvePendingApprovals(LeasedAgentRun run, String nodeId, HistoryState history,
                                            BudgetLimits limits) {
        List<PendingToolCall> unanswered = collectUnansweredToolCalls(history);
        if (unanswered.isEmpty()) {
            return false;
        }
        boolean stillWaiting = false;
        List<Map<String, Object>> resolvedMessages = new ArrayList<>();
        for (PendingToolCall call : unanswered) {
            NodeTool tool = toolRegistry.lookup(call.name()).orElse(null);
            if (tool == null) {
                resolvedMessages.add(toolResultMessage(call.id(), call.name(),
                        Map.of("error", "unknown tool: " + call.name())));
                continue;
            }
            Map<String, Object> arguments = readMap(call.argumentsJson());
            String argumentsDigest;
            try {
                argumentsDigest = bindingDigest(tool, arguments);
            } catch (IllegalArgumentException exception) {
                resolvedMessages.add(toolResultMessage(call.id(), call.name(),
                        Map.of("error", "invalid arguments: " + exception.getMessage())));
                continue;
            }
            Optional<ApprovalService.ApprovalGrant> grant = approvalService.findLatestByBinding(
                    run.tenantId(), run.id(), nodeId, tool.toolId(), argumentsDigest);
            Instant now = Instant.now();
            if (grant.isEmpty()) {
                // 崩溃恢复窗口：assistant 消息已落库但审批单尚未建立，补建后继续等待。
                requestApproval(run, nodeId, tool, arguments, null, tool.riskFloor(), null);
                stillWaiting = true;
                continue;
            }
            ApprovalService.ApprovalGrant approval = grant.get();
            if (approval.pending()) {
                stillWaiting = true;
            } else if (approval.rejected()) {
                store.markInvocationRejected(approval.toolInvocationId(), "APPROVAL_REJECTED");
                resolvedMessages.add(toolResultMessage(call.id(), call.name(),
                        Map.of("error", "approval_rejected",
                                "message", "A human approver rejected this action; "
                                        + "do not retry with the same arguments")));
            } else if (approval.actionable(now)) {
                if (!tool.readOnly() && writeBudgetExhausted(run, limits)) {
                    // 已批准但写预算触顶：不消费批准、不执行，回填预算错误交模型（或人工）处置。
                    resolvedMessages.add(toolResultMessage(call.id(), call.name(),
                            Map.of("error", "write_budget_exceeded",
                                    "message", "Approved action was not executed: write budget exhausted")));
                } else {
                    resolvedMessages.add(executeApproved(run, nodeId, tool, arguments, approval, call));
                }
            } else if (approval.approvedButExpired(now) || "EXPIRED".equals(approval.decision())) {
                if (approval.approvedButExpired(now)) {
                    approvalService.markExpired(run.tenantId(), run.id(), approval.id());
                }
                requestApproval(run, nodeId, tool, arguments,
                        approval.consumed() ? null : approval.toolInvocationId(),
                        approval.riskCeiling(), null);
                stillWaiting = true;
            } else {
                // APPROVED 且已消费：复用该 invocation 的终态，不重复执行。
                resolvedMessages.add(consumedResultMessage(call, approval));
            }
        }
        for (Map<String, Object> message : resolvedMessages) {
            history.messages.add(store.appendRunMessage(run, history.nextSequence++, message));
        }
        return stillWaiting;
    }

    /** AUTO 模式自动批准：占位 invocation + APPROVED 审批单（策略署名），返回可立即消费的授权。 */
    private ApprovalService.ApprovalGrant autoApprove(LeasedAgentRun run, String nodeId, NodeTool tool,
                                                      Map<String, Object> arguments,
                                                      DispatchGate.Decision decision,
                                                      String agentInvocationId) {
        Map<String, Object> validated = tool.validateArguments(arguments);
        String canonicalArguments;
        try {
            canonicalArguments = CanonicalJson.canonicalize(objectMapper.writeValueAsString(validated));
        } catch (Exception exception) {
            throw new IllegalArgumentException("tool arguments cannot be serialized", exception);
        }
        String invocationId = store.createAwaitingToolInvocation(
                run, agentInvocationId, nodeId, tool, canonicalArguments);
        String riskCeiling = decision.riskCode() != null ? decision.riskCode().name() : tool.riskFloor();
        String policyVersion = decision.policyDecision() != null
                ? decision.policyDecision().policyVersion() : null;
        return approvalService.createAutoApproval(run.tenantId(), run.id(), invocationId, nodeId,
                tool.toolId(), tool.version(), canonicalArguments, riskCeiling,
                run.rootAgentInstanceId(), agentInvocationId, run.humanPrincipalId(), policyVersion);
    }

    /** 占位 invocation（可复用未消费的旧占位）+ 审批单；令 approval 外键指向将来执行的那次调用。 */
    private void requestApproval(LeasedAgentRun run, String nodeId, NodeTool tool,
                                 Map<String, Object> arguments, String reuseInvocationId,
                                 String riskCeiling, String modelInvocationId) {
        Map<String, Object> validated = tool.validateArguments(arguments);
        String canonicalArguments;
        try {
            canonicalArguments = CanonicalJson.canonicalize(objectMapper.writeValueAsString(validated));
        } catch (Exception exception) {
            throw new IllegalArgumentException("tool arguments cannot be serialized", exception);
        }
        String invocationId = reuseInvocationId != null ? reuseInvocationId
                : store.createAwaitingToolInvocation(run, modelInvocationId, nodeId, tool, canonicalArguments);
        approvalService.createApproval(run.tenantId(), run.id(), invocationId, nodeId,
                tool.toolId(), tool.version(), canonicalArguments, riskCeiling, run.executionMode(),
                run.rootAgentInstanceId(), modelInvocationId, run.humanPrincipalId());
    }

    /** 带令牌执行已批准的调用；成败都回填为该 tool_call 的结果消息。 */
    private Map<String, Object> executeApproved(LeasedAgentRun run, String nodeId, NodeTool tool,
                                                Map<String, Object> arguments,
                                                ApprovalService.ApprovalGrant approval,
                                                PendingToolCall call) {
        // 令牌 claim 与 SubmitInvocation.handoff_id 都取该上下文值，二者必须一致；
        // 审批单锚定的模型调用缺失时用确定性占位值（不落库，仅存在于签名与线控字段）。
        String contextInvocationId = approval.agentInvocationId() != null
                && !approval.agentInvocationId().isBlank()
                ? approval.agentInvocationId()
                : "resume-" + approval.id();
        RuntimeToolContext context = new RuntimeToolContext(run, contextInvocationId, nodeId);
        try {
            store.renewLease(run.id(), Duration.ofSeconds(60));
            NodeMcpGateway.ToolOutcome outcome = mcpGateway.callApprovedTool(context, tool, arguments, approval);
            recordToolExecution(run, tool, true, "SUCCEEDED");
            return toolResultMessage(call.id(), call.name(), outcome.structuredOutput());
        } catch (NodeMcpGateway.ToolCallException toolException) {
            recordToolExecution(run, tool, false, toolException.getMessage());
            return toolResultMessage(call.id(), call.name(),
                    Map.of("error", toolException.getMessage(),
                            "error_code", toolException.errorCode(),
                            "retryable", toolException.retryable()));
        }
    }

    /** 已消费批准的重复 tool_call：复用占位 invocation 的终态，避免重复执行。 */
    private Map<String, Object> consumedResultMessage(PendingToolCall call,
                                                      ApprovalService.ApprovalGrant approval) {
        return store.findInvocationOutcome(approval.toolInvocationId())
                .map(outcome -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("status", outcome.status());
                    payload.put("reusedToolInvocationId", approval.toolInvocationId());
                    if (outcome.resultJson() != null && !outcome.resultJson().isBlank()) {
                        payload.put("result", readJson(outcome.resultJson()));
                    }
                    if (outcome.errorCode() != null && !outcome.errorCode().isBlank()) {
                        payload.put("error_code", outcome.errorCode());
                    }
                    return toolResultMessage(call.id(), call.name(), payload);
                })
                .orElseGet(() -> toolResultMessage(call.id(), call.name(),
                        Map.of("error", "approval_consumed",
                                "message", "Approval was already consumed by an earlier attempt")));
    }

    /** 历史中已提出但尚无结果消息的 tool_call（等待审批的调用不落占位结果）。 */
    private List<PendingToolCall> collectUnansweredToolCalls(HistoryState history) {
        LinkedHashMap<String, PendingToolCall> pending = new LinkedHashMap<>();
        for (AgentRuntimeStore.RunMessage message : history.messages) {
            Map<String, Object> body = message.message();
            Object role = body.get("role");
            if ("assistant".equals(role) && body.get("tool_calls") instanceof List<?> calls) {
                for (Object entry : calls) {
                    if (!(entry instanceof Map<?, ?> callMap) || callMap.get("id") == null
                            || !(callMap.get("function") instanceof Map<?, ?> function)) {
                        continue;
                    }
                    String id = String.valueOf(callMap.get("id"));
                    Object argumentsJson = function.get("arguments");
                    pending.put(id, new PendingToolCall(id, String.valueOf(function.get("name")),
                            argumentsJson == null ? "{}" : String.valueOf(argumentsJson)));
                }
            } else if ("tool".equals(role) && body.get("tool_call_id") != null) {
                pending.remove(String.valueOf(body.get("tool_call_id")));
            }
        }
        return List.copyOf(pending.values());
    }

    /** 本轮悬挂的绑定中是否已有任何一个被人工决定（决 WAITING 转换竞态）。 */
    private boolean anyPendingBindingDecided(LeasedAgentRun run, String nodeId, HistoryState history) {
        for (PendingToolCall call : collectUnansweredToolCalls(history)) {
            NodeTool tool = toolRegistry.lookup(call.name()).orElse(null);
            if (tool == null) {
                continue;
            }
            try {
                String digest = bindingDigest(tool, readMap(call.argumentsJson()));
                Optional<ApprovalService.ApprovalGrant> grant = approvalService.findLatestByBinding(
                        run.tenantId(), run.id(), nodeId, tool.toolId(), digest);
                if (grant.isPresent() && !grant.get().pending()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 无法归一的调用在恢复路径上会以错误结果回填，这里不参与竞态判断。
            }
        }
        return false;
    }

    /** 审批绑定摘要：canonical(validateArguments(args)) 的 SM3。 */
    private String bindingDigest(NodeTool tool, Map<String, Object> arguments) {
        try {
            return Sm3.hex(CanonicalJson.canonicalize(
                    objectMapper.writeValueAsString(tool.validateArguments(arguments))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("tool arguments cannot be serialized", exception);
        }
    }

    private record PendingToolCall(String id, String name, String argumentsJson) {}

    /** 执行记录挂钩：工具终态与 run 成功收尾写记忆链；记忆写入失败只记日志，不影响主流程。 */
    private void recordToolExecution(LeasedAgentRun run, NodeTool tool, boolean success, String summary) {
        try {
            memoryService.recordToolExecution(run, tool.toolId(), success, summary);
        } catch (RuntimeException memoryError) {
            log.warn("execution record hook failed for run {}: {}", run.id(), memoryError.getMessage());
        }
    }

    private void recordRunCompleted(LeasedAgentRun run) {
        try {
            memoryService.recordRunCompleted(run);
        } catch (RuntimeException memoryError) {
            log.warn("run-completed memory hook failed for run {}: {}", run.id(), memoryError.getMessage());
        }
    }

    /** 终态通知 SubAgent 闭环；根 run 无父时为 no-op，闭环异常不得反转已落库的终态。 */
    private void notifySubAgentTerminal(LeasedAgentRun run, boolean success, String errorCode, String message) {
        try {
            subAgentLifecycle.onRunTerminal(run, success, errorCode, message);
        } catch (RuntimeException lifecycleError) {
            log.error("SubAgent terminal handling failed for run {}", run.id(), lifecycleError);
        }
    }

    private static final class HistoryState {
        String summaryText;
        final List<AgentRuntimeStore.RunMessage> messages;
        long nextSequence;

        HistoryState(String summaryText, List<AgentRuntimeStore.RunMessage> messages, long nextSequence) {
            this.summaryText = summaryText;
            this.messages = messages;
            this.nextSequence = nextSequence;
        }
    }

    /** lease 恢复/唤醒后从库内重建会话消息与最新阶段摘要。 */
    private HistoryState restoreHistory(LeasedAgentRun run) {
        return new HistoryState(store.latestSummaryText(run.id()),
                new ArrayList<>(store.loadActiveRunMessages(run.id())),
                store.nextMessageSequence(run.id()));
    }

    /** 重启/唤醒恢复：从 checkpoint 的 round_no 继续，已用轮次不重复计入预算。 */
    private int restoreStartRound(LeasedAgentRun run) {
        Optional<RunCheckpointService.RunCheckpoint> checkpoint = checkpoints.load(run.id());
        if (checkpoint.isEmpty()) {
            return 0;
        }
        int startRound = Math.min(Math.max(0, checkpoint.get().roundNo()), maxAgenticRounds);
        log.info("AgentRun {} resumed from checkpoint: round {} (last tool invocation {})",
                run.id(), startRound, checkpoint.get().lastToolInvocationId());
        return startRound;
    }

    /** 每轮模型调用前写检查点；检查点失败不阻断执行。 */
    private void recordCheckpoint(LeasedAgentRun run, int round) {
        try {
            checkpoints.save(run, round, store.lastToolInvocationId(run.id()));
        } catch (RuntimeException exception) {
            log.error("AgentRun {} checkpoint write failed at round {}", run.id(), round, exception);
        }
    }

    /** 已完成的非幂等工具不再执行：按 run+tool+参数摘要命中 SUCCEEDED 调用并复用其结果。 */
    private Optional<Map<String, Object>> replayGuard(
            LeasedAgentRun run, NodeTool tool, Map<String, Object> arguments
    ) {
        if (tool.idempotency() == null || tool.idempotency() == ToolIdempotency.IDEMPOTENT) {
            return Optional.empty();
        }
        String argumentsDigest;
        try {
            argumentsDigest = Sm3.hex(CanonicalJson.canonicalize(
                    objectMapper.writeValueAsString(tool.validateArguments(arguments))));
        } catch (Exception exception) {
            return Optional.empty();
        }
        return store.findSucceededToolInvocation(run.id(), tool.toolId(), argumentsDigest)
                .map(completed -> {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("status", "SUCCEEDED");
                    output.put("reusedToolInvocationId", completed.id());
                    output.put("replayed", false);
                    if (completed.resultJson() != null && !completed.resultJson().isBlank()) {
                        output.put("result", readJson(completed.resultJson()));
                    }
                    return output;
                });
    }

    private Object readJson(String value) {
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception exception) {
            return value;
        }
    }

    /** stable/dynamic 段永驻不裁；超预算或消息段超阈值时，从最旧轮次压缩为摘要并落库。 */
    private void applyWindowBudget(LeasedAgentRun run, CompiledContext context, HistoryState history) {
        List<ContextWindowPolicy.Message> views = history.messages.stream()
                .map(message -> new ContextWindowPolicy.Message(message.sequenceNo(), message.tokenEstimate(),
                        "assistant".equals(message.message().get("role"))))
                .toList();
        ContextWindowPolicy.TrimPlan plan = windowPolicy.plan(context.tokenEstimate(), views);
        if (plan.compressSequences().isEmpty()) {
            return;
        }
        Set<Long> evicted = new HashSet<>(plan.compressSequences());
        List<Map<String, Object>> evictedMessages = history.messages.stream()
                .filter(message -> evicted.contains(message.sequenceNo()))
                .map(AgentRuntimeStore.RunMessage::message)
                .toList();
        String summaryText = contextSummarizer.summarize(history.summaryText, evictedMessages);
        long summaryTokens = estimateTokens(summaryText);
        long evictedTokens = history.messages.stream()
                .filter(message -> evicted.contains(message.sequenceNo()))
                .mapToLong(AgentRuntimeStore.RunMessage::tokenEstimate)
                .sum();
        long savedTokens = evictedTokens + estimateTokens(history.summaryText) - summaryTokens;
        store.saveContextSummary(run, history.nextSequence++, summaryText,
                plan.compressSequences(), summaryTokens, savedTokens);
        contextSummarizer.recordCompression(savedTokens);
        history.messages.removeIf(message -> evicted.contains(message.sequenceNo()));
        history.summaryText = summaryText;
    }

    /** 摘要段永远位于消息段最前；stable/dynamic 段由 CompiledContext 保持全局最前。 */
    private List<Map<String, Object>> renderExtraMessages(HistoryState history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (history.summaryText != null) {
            PromptSegment segment = contextSummarizer.summarySegment(history.summaryText);
            messages.add(Map.of("role", segment.role(), "content", segment.content()));
        }
        for (AgentRuntimeStore.RunMessage message : history.messages) {
            messages.add(message.message());
        }
        return messages;
    }

    private long estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : Math.max(1, (text.length() + 3L) / 4L);
    }

    private Map<String, Object> toolResultMessage(String toolCallId, String toolName, Map<String, Object> output) {
        return Map.of("role", "tool", "tool_call_id", toolCallId,
                "name", toolName, "content", jsonString(output));
    }

    private String jsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private boolean grantAllows(Map<String, Object> grant, NodeTool tool) {
        if (grantReadOnly(grant) && !tool.readOnly()) {
            return false;
        }
        List<String> allowedIds = grantToolIds(grant);
        return allowedIds.isEmpty() || allowedIds.contains(tool.toolId());
    }

    @SuppressWarnings("unchecked")
    private List<String> grantToolIds(Map<String, Object> grant) {
        Object tools = grant.get("tools");
        if (tools instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private boolean grantReadOnly(Map<String, Object> grant) {
        return Boolean.TRUE.equals(grant.get("readOnly"));
    }

    @SuppressWarnings("unchecked")
    private List<String> grantNodeIds(Map<String, Object> grant) {
        Object nodes = grant.get("nodes");
        if (nodes instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String errorCode(Exception exception) {
        if (exception instanceof NodeMcpGateway.ToolCallException toolException) {
            return toolException.errorCode();
        }
        if (exception instanceof IllegalArgumentException) {
            return "CONTRACT_VALIDATION_FAILED";
        }
        return "AGENT_RUNTIME_FAILED";
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid runtime JSON", exception);
        }
    }

    private String required(Map<String, Object> values, String key) {
        String value = String.valueOf(values.getOrDefault(key, "")).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("target scope is missing " + key);
        }
        return value;
    }

    private void validateBudget(String budgetJson) {
        Map<String, Object> budget = readMap(budgetJson);
        requireBudgetAtLeast(budget, "maxModelInvocations", 1);
        requireBudgetAtLeast(budget, "maxToolInvocations", 1);
        requireBudgetAtLeast(budget, "maxInputTokens", 1);
        requireBudgetAtLeast(budget, "maxOutputTokens", 1);
        requireBudgetAtLeast(budget, "maxWallClockSeconds", 1);
    }

    private void requireBudgetAtLeast(Map<String, Object> budget, String key, long minimum) {
        Object value = budget.get(key);
        if (!(value instanceof Number number) || number.longValue() < minimum) {
            throw new IllegalArgumentException("AgentRun budget does not permit " + key);
        }
    }
}

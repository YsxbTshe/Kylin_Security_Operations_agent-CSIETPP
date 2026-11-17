package com.superskidder.kylinops.backend.agentruntime.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.memory.KnowledgeSearchTool;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.registry.AgentRegistryEntry;
import com.superskidder.kylinops.backend.registry.AgentRegistryService;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillLoadTool;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillSearchTool;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity;
import com.superskidder.kylinops.backend.agentruntime.vfs.VfsReadTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * RUNTIME 执行器分流：RUNTIME 类工具（如 recruit_agent）不下发节点，而在 Runtime 内执行。
 *
 * <p>招募类（recruit_agent）依次做：执行限制检查 → 创建子 agent_instance → 创建 handoff
 * → 创建子 agent_run → 父 run 置 WAITING/SUB_AGENT，并返回结构化结果作为工具输出。
 *
 * <p>只读类（knowledge_search / skill_search / skill_load / vfs_read）同步完成，不挂起 run；
 * 各自的领域异常在这里翻译成稳定错误码，绝不静默降级为空结果——空结果会被模型读成
 * "查过了，没有"，而事实是"没查到/被拒绝"。
 */
@Component
public class RuntimeToolDispatcher {

    private final AgentRuntimeStore store;
    private final HandoffService handoffService;
    private final ObjectMapper objectMapper;
    private final AgentRegistryService agentRegistryService;

    public RuntimeToolDispatcher(AgentRuntimeStore store, HandoffService handoffService,
                                ObjectMapper objectMapper, AgentRegistryService agentRegistryService) {
        this.store = store;
        this.handoffService = handoffService;
        this.objectMapper = objectMapper;
        this.agentRegistryService = agentRegistryService;
    }

    /**
     * RUNTIME 工具的执行结果。
     *
     * <p>{@code runSuspended} 是执行循环的分流依据：RUNTIME 工具**并非都是异步**——
     * 招募子 Agent 会把 run 转入 WAITING（要等子 run 终态才有结果），而知识检索是同步的，
     * 结果随本次调用一并返回。此前执行循环对所有 RUNTIME 工具一律置等待，
     * knowledge_search 之后 run 既不在 WAITING 也不继续下一轮，只能干等下一次轮询/租约超时
     * （现场表现为约 60 秒静默停顿）。由 dispatcher 如实回报"我有没有把 run 挂起"，
     * 而不是让调用方按工具名猜。
     */
    public record DispatchOutcome(String handoffId, String agentInstanceId, String childRunId,
                                  Map<String, Object> structuredOutput, Map<String, Object> renderedOutput,
                                  boolean runSuspended) {

        /** 同步返回结果的 RUNTIME 工具：结果已在手，run 未被挂起，应直接进下一轮。 */
        public static DispatchOutcome completed(Map<String, Object> structuredOutput,
                                                Map<String, Object> renderedOutput) {
            return new DispatchOutcome(null, null, null, structuredOutput, renderedOutput, false);
        }

        /** 已把 run 转入等待态的 RUNTIME 工具（子 Agent 派生等）：本轮到此为止，等外部事件唤醒。 */
        public static DispatchOutcome suspended(String handoffId, String agentInstanceId, String childRunId,
                                                Map<String, Object> structuredOutput,
                                                Map<String, Object> renderedOutput) {
            return new DispatchOutcome(handoffId, agentInstanceId, childRunId,
                    structuredOutput, renderedOutput, true);
        }
    }

    public DispatchOutcome dispatch(LeasedAgentRun run, NodeTool tool, Map<String, Object> arguments) {
        try {
            if (tool instanceof RecruitAgentTool recruitTool) {
                return recruit(run, recruitTool, arguments);
            }
            if (tool instanceof KnowledgeSearchTool knowledgeTool) {
                return searchKnowledge(run, knowledgeTool, arguments);
            }
            if (tool instanceof SkillSearchTool skillSearchTool) {
                return searchSkills(run, skillSearchTool, arguments);
            }
            if (tool instanceof SkillLoadTool skillLoadTool) {
                return loadSkill(run, skillLoadTool, arguments);
            }
            if (tool instanceof VfsReadTool vfsReadTool) {
                return readVfs(run, vfsReadTool, arguments);
            }
            throw new SubAgentContractException("RUNTIME_TOOL_UNSUPPORTED",
                    "unsupported runtime tool: " + tool.toolId());
        } catch (SubAgentContractException contractException) {
            throw new NodeMcpGateway.ToolCallException(
                    contractException.errorCode(), contractException.getMessage(), false);
        }
    }

    private DispatchOutcome recruit(LeasedAgentRun run, RecruitAgentTool tool, Map<String, Object> arguments) {
        RecruitAgentTool.RecruitRequest request = tool.requestFrom(arguments);
        requireRegisteredExpert(run.tenantId(), request.agentKey());

        AgentRuntimeStore.AgentInstanceView parentInstance = store.findAgentInstance(run.rootAgentInstanceId())
                .orElseThrow(() -> new SubAgentContractException("SUB_AGENT_INSTANCE_NOT_FOUND",
                        "recruiting agent instance not found: " + run.rootAgentInstanceId()));

        SubAgentLimits.Decision decision = SubAgentLimits.evaluate(
                parentInstance.depth() + 1,
                store.countSubAgents(run.id(), true),
                store.countSubAgents(run.id(), false));
        if (!decision.allowed()) {
            throw new SubAgentContractException(decision.errorCode(), decision.reason());
        }

        AgentSpecSnapshot spec = resolveSpec(request);
        Map<String, Object> parentGrant = readMap(run.grantSnapshotJson());
        // 父预算取库中实时值（同一租约内可能已被先前招募扣减）。
        Map<String, Object> parentBudget = handoffService.runBudget(run.id());
        Map<String, Object> usage = handoffService.runUsage(run.id());
        // recruit_agent 入参不含 grant：子 Agent 继承父 grant（结构化保证 ⊆ 父 grant）。
        Map<String, Object> childGrant = handoffService.attenuateGrant(parentGrant, null);
        Map<String, Object> childBudget = handoffService.attenuateBudget(parentBudget, usage, request.budget());

        String childInstanceId = store.insertSubAgentInstance(run, parentInstance.id(),
                parentInstance.depth() + 1, request.goal(), definitionSnapshotJson(spec), childGrant, childBudget);
        HandoffService.Handoff handoff = handoffService.createHandoff(run, parentInstance.id(), childInstanceId,
                request.goal(), spec, null, request.budget(), request.outputSchema());
        String childRunId = store.insertSubAgentRun(run, childInstanceId, request.goal(), childGrant, childBudget);
        // 预算口径：招募即预扣子预算；子终态时归还未用部分（见 SubAgentLifecycleService）。
        store.deductRunBudget(run.id(), childBudget);
        store.transitionToSubAgentWaiting(run);

        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("handoffId", handoff.id());
        structuredOutput.put("agentInstanceId", childInstanceId);
        structuredOutput.put("childRunId", childRunId);
        structuredOutput.put("status", HandoffService.STATUS_OPEN);
        // 上面已 transitionToSubAgentWaiting：父 run 确实被挂起，如实回报。
        return DispatchOutcome.suspended(handoff.id(), childInstanceId, childRunId,
                structuredOutput, tool.render(structuredOutput));
    }

    private DispatchOutcome searchKnowledge(LeasedAgentRun run, KnowledgeSearchTool tool, Map<String, Object> arguments) {
        try {
            Map<String, Object> validated = tool.validateArguments(arguments);
            Map<String, Object> structuredOutput = tool.search(run.tenantId(), validated);
            // 知识检索同步完成：不转 WAITING，执行循环拿到结果后直接进下一轮。
            return DispatchOutcome.completed(structuredOutput, tool.render(structuredOutput));
        } catch (IllegalArgumentException validation) {
            throw new SubAgentContractException("KNOWLEDGE_SEARCH_INVALID", validation.getMessage());
        }
    }

    /**
     * kylin.skill.search：渐进式披露第一层，同步返回，只出元数据。
     *
     * <p>失败不 fail-open：注册表读不到时返回空列表会被模型读成"本租户没有 Skill"，
     * 这里统一收敛为结构化错误码，让模型看得见"检索失败"与"没有匹配"的区别。
     */
    private DispatchOutcome searchSkills(LeasedAgentRun run, SkillSearchTool tool,
                                         Map<String, Object> arguments) {
        Map<String, Object> validated;
        try {
            validated = tool.validateArguments(arguments);
        } catch (RuntimeException validation) {
            throw new SubAgentContractException("SKILL_SEARCH_INVALID", reason(validation));
        }
        try {
            Map<String, Object> structuredOutput = tool.search(run.tenantId(), validated);
            return DispatchOutcome.completed(structuredOutput, tool.render(structuredOutput));
        } catch (RuntimeException failure) {
            throw new SubAgentContractException("SKILL_SEARCH_FAILED", reason(failure));
        }
    }

    /**
     * kylin.skill.load：渐进式披露第二/三层。未注册、未发布、已隔离、越权路径都在
     * SkillProgressiveDisclosure 内判定，这里只负责把异常翻译成稳定的错误码，
     * 路径类违规单独成码（SKILL_PATH_REJECTED），便于审计区分"读不到"与"想越界"。
     */
    private DispatchOutcome loadSkill(LeasedAgentRun run, SkillLoadTool tool,
                                      Map<String, Object> arguments) {
        Map<String, Object> validated;
        try {
            validated = tool.validateArguments(arguments);
        } catch (RuntimeException validation) {
            throw new SubAgentContractException("SKILL_LOAD_INVALID", reason(validation));
        }
        try {
            Map<String, Object> structuredOutput = tool.load(run.tenantId(), validated);
            return DispatchOutcome.completed(structuredOutput, tool.render(structuredOutput));
        } catch (PathSecurity.VfsPathSecurityException pathViolation) {
            throw new SubAgentContractException("SKILL_PATH_REJECTED", reason(pathViolation));
        } catch (RuntimeException failure) {
            throw new SubAgentContractException("SKILL_LOAD_REJECTED", reason(failure));
        }
    }

    /**
     * kylin.vfs.read：委托 VfsService。三类失败分开编码——
     * 路径安全违规 / 未实现的读取路径（workspace runner 未连通、archive 成员读取）/ 其他拒绝，
     * 任何一类都不会退化成"空内容成功"。
     */
    private DispatchOutcome readVfs(LeasedAgentRun run, VfsReadTool tool, Map<String, Object> arguments) {
        Map<String, Object> validated;
        try {
            validated = tool.validateArguments(arguments);
        } catch (RuntimeException validation) {
            throw new SubAgentContractException("VFS_READ_INVALID", reason(validation));
        }
        try {
            Map<String, Object> structuredOutput = tool.read(
                    run.tenantId(), run.rootAgentInstanceId(), validated);
            return DispatchOutcome.completed(structuredOutput, tool.render(structuredOutput));
        } catch (PathSecurity.VfsPathSecurityException pathViolation) {
            throw new SubAgentContractException("VFS_PATH_REJECTED", reason(pathViolation));
        } catch (UnsupportedOperationException unsupported) {
            throw new SubAgentContractException("VFS_READ_UNSUPPORTED", reason(unsupported));
        } catch (RuntimeException failure) {
            throw new SubAgentContractException("VFS_READ_REJECTED", reason(failure));
        }
    }

    private static String reason(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /**
     * 招募必须声明一个**已注册且启用**的专家身份。
     *
     * <p>此前 agent_key 可省略，而省略即无身份、不受能力封套约束——等于把「要不要接受
     * 约束」交给被约束方自己决定。现在把它变成招募的前置条件：未注册、已停用、或写了
     * 一个不存在的 key，招募当场失败，子 Agent 根本不会被创建。
     *
     * <p>拒绝信息里列出该租户当前可用的专家 key。这一条是刻意的：模型此前只能从工具
     * 描述里看到几个示例 key，猜错就只能反复失败；把可选项放进错误信息，模型下一轮就能
     * 自己纠正。列表只含已启用的，且只暴露 key 与角色，不泄露封套细节。
     */
    private void requireRegisteredExpert(String tenantId, String agentKey) {
        List<AgentRegistryEntry> enabled = agentRegistryService.listAgents(tenantId).stream()
                .filter(AgentRegistryEntry::enabled)
                .toList();
        boolean known = enabled.stream()
                .anyMatch(entry -> entry.agentKey() != null && entry.agentKey().equalsIgnoreCase(agentKey));
        if (known) {
            return;
        }
        String available = enabled.isEmpty()
                ? "（该组织尚未注册任何启用的专家 Agent，请先在专家注册表中登记）"
                : enabled.stream()
                        .map(entry -> entry.agentKey() + "(" + entry.role() + ")")
                        .collect(java.util.stream.Collectors.joining(", "));
        throw new SubAgentContractException("SUB_AGENT_UNKNOWN_EXPERT",
                "agent_key 未注册或已停用: " + agentKey + "；可用专家: " + available);
    }

    private AgentSpecSnapshot resolveSpec(RecruitAgentTool.RecruitRequest request) {
        // 动态规格仅内存传递，不写 agent_definition/agent_definition_version。
        String instructions = request.specInstructions() == null || request.specInstructions().isBlank()
                ? RecruitAgentTool.DEFAULT_DYNAMIC_INSTRUCTIONS
                : request.specInstructions();
        return AgentSpecSnapshot.dynamic(instructions, List.of(), Map.of());
    }

    private String definitionSnapshotJson(AgentSpecSnapshot spec) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("source", "dynamic");
        snapshot.put("instructions", spec.instructions());
        snapshot.put("allowedSkills", spec.allowedSkills());
        snapshot.put("defaultBudget", spec.defaultBudget());
        snapshot.put("contentDigest", spec.contentDigest());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("spec snapshot cannot be serialized", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new SubAgentContractException("SUB_AGENT_CONTRACT_INVALID", "invalid runtime JSON");
        }
    }
}

package com.superskidder.kylinops.backend.agentruntime.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SubAgent 完成闭环（Phase 5.3）：
 * 子 run 终态 → 按 output_schema 关闭 handoff → 归还未用预算 → 汇总 artifact 引用进父上下文帧
 * → 父无活跃子时从 WAITING/SUB_AGENT 唤醒。
 * 取消传播：级联取消未完成子 run 与 OPEN handoff，预算归还，被单独取消的子唤醒父。
 * 红线：父与子互不读取对方原始上下文，只经 handoff 结果与 artifact 引用传递。
 * 预算口径：招募时从父 budget_json 预扣子预算；终态归还 max(0, 子预算-子用量)，
 * 由 handoff 状态流转保证只归还一次，故任意时刻父预算 ≤ 原预算。
 */
@Component
public class SubAgentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SubAgentLifecycleService.class);
    private static final List<String> TERMINAL_STATUSES = List.of("COMPLETED", "FAILED", "CANCELLED");

    private final AgentRuntimeStore store;
    private final HandoffService handoffService;
    private final ObjectMapper objectMapper;

    public SubAgentLifecycleService(AgentRuntimeStore store, HandoffService handoffService, ObjectMapper objectMapper) {
        this.store = store;
        this.handoffService = handoffService;
        this.objectMapper = objectMapper;
    }

    public record CancelReport(List<String> cancelledRunIds, List<String> cancelledHandoffIds) {}

    /** 子 run 到达终态后由 worker 调用；根 run（无父）或 handoff 已关闭时为空操作。 */
    public void onRunTerminal(LeasedAgentRun run, boolean success, String errorCode, String message) {
        Optional<AgentRuntimeStore.RunCore> self = store.findRunCore(run.id());
        if (self.isEmpty() || self.get().parentRunId() == null) {
            return;
        }
        Optional<String> handoffId = store.findOpenHandoffIdForChild(run.rootAgentInstanceId());
        if (handoffId.isEmpty()) {
            return;
        }
        String parentRunId = self.get().parentRunId();

        String handoffStatus;
        Map<String, Object> failure = new LinkedHashMap<>();
        if (success) {
            try {
                handoffService.completeHandoff(handoffId.get(), store.finalResultJson(run.id()));
                handoffStatus = HandoffService.STATUS_COMPLETED;
            } catch (SubAgentContractException contractException) {
                // schema 校验失败：completeHandoff 已置 FAILED；补齐结构化失败原因，父收到失败而非脏数据。
                handoffService.failHandoff(handoffId.get(), contractException.errorCode(), contractException.getMessage());
                handoffStatus = HandoffService.STATUS_FAILED;
                failure.put("errorCode", contractException.errorCode());
                failure.put("message", contractException.getMessage());
            }
        } else {
            handoffService.failHandoff(handoffId.get(), errorCode, message);
            handoffStatus = HandoffService.STATUS_FAILED;
            failure.put("errorCode", errorCode);
            failure.put("message", message == null ? "" : message);
        }

        refundUnusedBudget(run.id(), parentRunId);
        List<AgentRuntimeStore.ArtifactRef> artifactRefs =
                store.publishedArtifactRefs(run.tenantId(), run.rootAgentInstanceId());
        writeParentWakeContext(parentRunId, run, handoffId.get(), handoffStatus, failure, artifactRefs);

        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("handoffId", handoffId.get());
        eventPayload.put("childRunId", run.id());
        eventPayload.put("childInstanceId", run.rootAgentInstanceId());
        eventPayload.put("handoffStatus", handoffStatus);
        eventPayload.put("artifactRefs", artifactRefs.stream().map(AgentRuntimeStore.ArtifactRef::artifactId).toList());
        store.appendEvent(parentRunId, run.tenantId(), "SUB_AGENT_RESULT", "AGENT", run.id(), eventPayload);

        maybeWakeParent(parentRunId);
    }

    /**
     * 取消 run 及其全部未完成子孙：先深后浅，逐个 CANCELLED handoff → 归还预算 → 取消 run。
     * 被取消 run 若有父，则唤醒父（父看到取消结果）。
     */
    public CancelReport cancelRun(String runId, String actorType, String actorId, String reason) {
        List<String> cancelledRuns = new ArrayList<>();
        List<String> cancelledHandoffs = new ArrayList<>();
        List<String> tree = collectTree(runId);
        for (int index = tree.size() - 1; index >= 0; index--) {
            String currentId = tree.get(index);
            AgentRuntimeStore.RunCore core = store.findRunCore(currentId).orElse(null);
            if (core == null || TERMINAL_STATUSES.contains(core.status())) {
                continue;
            }
            Optional<String> handoffId = store.findOpenHandoffIdForChild(core.rootAgentInstanceId());
            handoffId.ifPresent(id -> {
                try {
                    handoffService.cancelHandoff(id);
                    cancelledHandoffs.add(id);
                } catch (SubAgentContractException ignored) {
                    // 并发下已被其他路径关闭，跳过。
                }
            });
            if (core.parentRunId() != null) {
                refundUnusedBudget(currentId, core.parentRunId());
            }
            if (!store.cancelRunById(currentId, reason)) {
                continue;
            }
            cancelledRuns.add(currentId);
            if (core.parentRunId() != null) {
                store.appendEvent(core.parentRunId(), core.tenantId(), "SUB_AGENT_RESULT",
                        actorType, actorId, Map.of(
                                "handoffId", handoffId.orElse(""),
                                "childRunId", currentId,
                                "childInstanceId", core.rootAgentInstanceId(),
                                "handoffStatus", HandoffService.STATUS_CANCELLED));
            }
        }
        AgentRuntimeStore.RunCore top = store.findRunCore(runId).orElse(null);
        if (top != null && top.parentRunId() != null) {
            maybeWakeParent(top.parentRunId());
        }
        return new CancelReport(cancelledRuns, cancelledHandoffs);
    }

    private void refundUnusedBudget(String childRunId, String parentRunId) {
        Map<String, Object> childBudget = handoffService.runBudget(childRunId);
        Map<String, Object> childUsage = handoffService.runUsage(childRunId);
        store.refundRunBudget(parentRunId, handoffService.unusedBudget(childBudget, childUsage));
    }

    private void maybeWakeParent(String parentRunId) {
        if (store.countActiveChildRuns(parentRunId) == 0) {
            store.wakeFromSubAgentWaiting(parentRunId);
        }
    }

    private void writeParentWakeContext(
            String parentRunId,
            LeasedAgentRun childRun,
            String handoffId,
            String handoffStatus,
            Map<String, Object> failure,
            List<AgentRuntimeStore.ArtifactRef> artifactRefs
    ) {
        AgentRuntimeStore.RunCore parent = store.findRunCore(parentRunId).orElse(null);
        if (parent == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "sub-agent-result");
        payload.put("handoffId", handoffId);
        payload.put("childRunId", childRun.id());
        payload.put("childInstanceId", childRun.rootAgentInstanceId());
        payload.put("handoffStatus", handoffStatus);
        if (HandoffService.STATUS_COMPLETED.equals(handoffStatus)) {
            payload.put("result", readNullableMap(store.finalResultJson(childRun.id())));
        } else {
            payload.put("failure", failure);
        }
        payload.put("artifactRefs", artifactRefs.stream().map(ref -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("artifactId", ref.artifactId());
            entry.put("name", ref.name());
            entry.put("sm3Hex", ref.sm3Hex() == null ? "" : ref.sm3Hex());
            return entry;
        }).toList());
        try {
            store.saveSubAgentWakeContext(parent, parent.rootAgentInstanceId(), payload);
        } catch (RuntimeException exception) {
            // 上下文帧失败不阻断状态机：handoff 结果仍是权威存储。
            log.error("failed to persist sub-agent wake context for parent {}", parentRunId, exception);
        }
    }

    private List<String> collectTree(String runId) {
        List<String> tree = new ArrayList<>();
        tree.add(runId);
        List<String> frontier = List.of(runId);
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                next.addAll(store.findChildRunIds(id));
            }
            tree.addAll(next);
            frontier = next;
        }
        return tree;
    }

    private Map<String, Object> readNullableMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of();
        }
    }
}

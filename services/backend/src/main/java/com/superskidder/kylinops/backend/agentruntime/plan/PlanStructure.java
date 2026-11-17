package com.superskidder.kylinops.backend.agentruntime.plan;

import java.time.Instant;
import java.util.List;

/**
 * 结构化计划视图：把 plan_version 的 Markdown 正文解析成「阶段 → 步骤」，
 * 并与该 run 的真实 {@code tool_invocation} 对账，供前端 Plan Canvas 渲染。
 *
 * <p><b>状态从哪来</b>：{@code status} 不是计划文档里写的，也不是猜的——
 * 步骤只要能对上一条真实的工具调用，状态就取那条调用的状态；对不上就是 PENDING。
 * 这条规则是整个 Canvas 可信度的基础：画面上任何一个「已完成」都能顺着
 * {@code toolInvocationId} 追到一条真实执行记录。
 */
public final class PlanStructure {

    private PlanStructure() {
    }

    /** 步骤/阶段状态。取值刻意与 tool_invocation.status 对齐，避免二次映射引入歧义。 */
    public enum NodeStatus {
        /** 计划里有，但还没有对应的执行记录。 */
        PENDING,
        RUNNING,
        DONE,
        /** 被门禁拒绝或计划中标记跳过。 */
        SKIPPED,
        FAILED
    }

    public record PlanStepView(
            String id,
            String title,
            String toolId,
            String nodeId,
            String riskCode,
            boolean requiresApproval,
            NodeStatus status,
            /** 计划步骤 ↔ 实际执行的对账键；为 null 表示这一步还没落地。 */
            String toolInvocationId,
            /** 被门禁拒绝时的拒绝码，便于「计划为什么没走通」一眼可见。 */
            String rejectionCode
    ) {}

    public record PlanPhaseView(
            String id,
            String title,
            int order,
            /** 其它 phase id，构成阶段 DAG。解析自 `> depends: A, B` 行。 */
            List<String> dependsOn,
            NodeStatus status,
            List<PlanStepView> steps
    ) {}

    public record PlanCanvasView(
            String id,
            String runId,
            int version,
            Integer baseVersion,
            String contentDigest,
            Instant createdAt,
            String createdBy,
            List<PlanPhaseView> phases,
            /** 解析统计：让前端能如实提示「这份计划有多少步没能结构化」。 */
            int parsedStepCount,
            int matchedStepCount,
            /** 正文里未能识别为阶段/步骤的行数，>0 时前端提示计划格式偏离约定。 */
            int unparsedLineCount
    ) {}
}

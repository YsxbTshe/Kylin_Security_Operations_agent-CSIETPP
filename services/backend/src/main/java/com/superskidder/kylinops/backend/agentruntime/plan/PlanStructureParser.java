package com.superskidder.kylinops.backend.agentruntime.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把计划 Markdown 解析成阶段 / 步骤。
 *
 * <p><b>为什么是解析而不是新建结构化模型</b>：plan_version 存的是人写的 Markdown
 * （{@code content_markdown}，不可变版本化）。为了 Canvas 去加一张阶段/步骤表，
 * 就会出现「文档」与「结构」两份真相，AMEND 时必然漂移。这里选择让 Markdown 保持唯一真相，
 * 结构是它的**投影**——计划怎么改，Canvas 就怎么变，不存在同步问题。
 *
 * <p><b>约定</b>（不合约定的行不会被丢掉，会计入 unparsedLineCount 让前端如实提示）：
 * <pre>
 * ## 阶段标题
 * &gt; depends: 阶段A, 阶段B          ← 可选，阶段间依赖，写阶段标题
 * - [ ] 步骤标题 `kylin.disk.usage` @node-kylin-01 (R3) [需审批]
 * - [x] 已完成的步骤                 ← [x] 只是作者笔记，真实状态仍以执行记录为准
 * </pre>
 *
 * <ul>
 *   <li>反引号包裹且形如 {@code kylin.*} 的 token → toolId</li>
 *   <li>{@code @xxx} → nodeId</li>
 *   <li>{@code (R0)}–{@code (R5)} → riskCode</li>
 *   <li>{@code [需审批]} / {@code [approval]} → requiresApproval</li>
 * </ul>
 *
 * <p>没有 {@code ##} 标题时，全部步骤归入一个隐式阶段「计划」，避免空 Canvas。
 */
final class PlanStructureParser {

    /** ## 或 ### 都当阶段，避免作者用了三级标题就整份解析不出来。 */
    private static final Pattern PHASE = Pattern.compile("^\\s{0,3}#{2,3}\\s+(.+?)\\s*$");
    private static final Pattern STEP = Pattern.compile("^\\s*[-*]\\s+(?:\\[([ xX])]\\s*)?(.+?)\\s*$");
    private static final Pattern DEPENDS = Pattern.compile("^\\s*>\\s*depends\\s*[:：]\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOOL = Pattern.compile("`\\s*(kylin\\.[a-zA-Z0-9._]+)\\s*`");
    private static final Pattern NODE = Pattern.compile("@([A-Za-z0-9][A-Za-z0-9._-]*)");
    private static final Pattern RISK = Pattern.compile("\\((R[0-5])\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPROVAL = Pattern.compile("\\[\\s*(需审批|approval|需要审批)\\s*]",
            Pattern.CASE_INSENSITIVE);
    /** 围栏代码块：块内不解析，否则示例命令会被当成步骤。 */
    private static final Pattern FENCE = Pattern.compile("^\\s*```");

    private PlanStructureParser() {
    }

    record ParsedPlan(List<PlanStructure.PlanPhaseView> phases, int stepCount, int unparsedLines) {}

    static ParsedPlan parse(String markdown) {
        List<PhaseDraft> phases = new ArrayList<>();
        PhaseDraft current = null;
        int unparsed = 0;
        int stepSeq = 0;
        boolean inFence = false;

        for (String rawLine : markdown.split("\r?\n", -1)) {
            String line = rawLine;
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
                continue;
            }
            if (inFence || line.isBlank()) {
                continue;
            }

            Matcher phase = PHASE.matcher(line);
            if (phase.matches()) {
                current = new PhaseDraft("phase-" + (phases.size() + 1), stripMarks(phase.group(1)), phases.size());
                phases.add(current);
                continue;
            }

            Matcher depends = DEPENDS.matcher(line);
            if (depends.matches() && current != null) {
                for (String token : depends.group(1).split("[,，]")) {
                    String dep = stripMarks(token);
                    if (!dep.isBlank()) {
                        current.dependsOnTitles.add(dep);
                    }
                }
                continue;
            }

            Matcher step = STEP.matcher(line);
            if (step.matches()) {
                if (current == null) {
                    // 没有 ## 标题就先开一个隐式阶段，而不是丢掉这些步骤。
                    current = new PhaseDraft("phase-1", "计划", 0);
                    phases.add(current);
                }
                current.steps.add(toStep("step-" + (++stepSeq), step.group(2)));
                continue;
            }

            // 普通说明文字：不是错误，但要如实计数，让前端能提示「这份计划有多少内容没进 Canvas」。
            if (!line.startsWith(">") && !line.startsWith("#")) {
                unparsed++;
            }
        }

        Map<String, String> titleToId = new LinkedHashMap<>();
        for (PhaseDraft draft : phases) {
            titleToId.putIfAbsent(draft.title.toLowerCase(Locale.ROOT), draft.id);
        }
        List<PlanStructure.PlanPhaseView> result = new ArrayList<>();
        int total = 0;
        for (PhaseDraft draft : phases) {
            List<String> deps = new ArrayList<>();
            for (String title : draft.dependsOnTitles) {
                String id = titleToId.get(title.toLowerCase(Locale.ROOT));
                // 指向不存在的阶段就丢弃：宁可少一条连线，也不要画一条指向空气的依赖。
                if (id != null && !id.equals(draft.id)) {
                    deps.add(id);
                }
            }
            total += draft.steps.size();
            result.add(new PlanStructure.PlanPhaseView(draft.id, draft.title, draft.order, List.copyOf(deps),
                    PlanStructure.NodeStatus.PENDING, List.copyOf(draft.steps)));
        }
        return new ParsedPlan(List.copyOf(result), total, unparsed);
    }

    private static PlanStructure.PlanStepView toStep(String id, String body) {
        String toolId = firstGroup(TOOL.matcher(body));
        String riskCode = firstGroup(RISK.matcher(body));
        boolean approval = APPROVAL.matcher(body).find();

        // nodeId 要在剥掉工具反引号之后再找，否则 `a@b` 之类会误命中。
        String withoutTool = TOOL.matcher(body).replaceAll(" ");
        String nodeId = firstGroup(NODE.matcher(withoutTool));

        String title = stripMarks(body
                .replaceAll(TOOL.pattern(), " ")
                .replaceAll(RISK.pattern(), " ")
                .replaceAll(APPROVAL.pattern(), " ")
                .replaceAll(NODE.pattern(), " "));
        if (title.isBlank()) {
            title = toolId != null ? toolId : "未命名步骤";
        }
        return new PlanStructure.PlanStepView(id, title, toolId, nodeId,
                riskCode == null ? null : riskCode.toUpperCase(Locale.ROOT), approval,
                PlanStructure.NodeStatus.PENDING, null, null);
    }

    private static String firstGroup(Matcher matcher) {
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 去掉 Markdown 强调符与多余空白，让标题在 Canvas 上是干净的一行。
     *
     * <p>只剥 {@code *} 与 {@code `}，**不剥下划线**：计划里常出现 {@code rollback_id}、
     * {@code content_digest} 这类标识符，一律剥掉会把它们改写成错的词
     * （曾把「按 rollback_id 恢复」显示成「按 rollbackid 恢复」）。
     * 下划线做强调在中文文档里几乎不用，宁可漏剥也不要改写标识符。
     */
    private static String stripMarks(String text) {
        return text.replaceAll("[*`]", "").replaceAll("\\s+", " ").trim();
    }

    private static final class PhaseDraft {
        private final String id;
        private final String title;
        private final int order;
        private final List<String> dependsOnTitles = new ArrayList<>();
        private final List<PlanStructure.PlanStepView> steps = new ArrayList<>();

        private PhaseDraft(String id, String title, int order) {
            this.id = id;
            this.title = title;
            this.order = order;
        }
    }
}

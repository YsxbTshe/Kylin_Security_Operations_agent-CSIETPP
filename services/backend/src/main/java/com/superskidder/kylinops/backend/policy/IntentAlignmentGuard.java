package com.superskidder.kylinops.backend.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 意图一致性护栏：确定性校验"用户自然语言意图"与"实际请求的工具动作"是否一致。
 *
 * <p>定位在 {@link PolicyEngine#evaluate} 之后、审批/下发之前（DispatchGate 接线）。
 * 只处理一种不对齐形态——用户表达的是<b>纯咨询意图</b>（只要建议、只要答案），
 * 而规划层却请求了一个<b>写工具</b>。这是 LLM 规划幻觉与注入劫持最常见的越权路径：
 * 用户只想要建议，Agent 却直接去执行变更。
 *
 * <p><b>为什么是子句级而不是整段匹配</b>：运维最自然的表达就是"先诊断后处置"——
 * 「检查磁盘并清理过期日志」「看看 nginx 挂没挂，挂了就重启」。整段只要出现"检查/看看"
 * 就把写工具判 R5，等于把这类复合意图整类禁掉（官方演示场景首当其冲）。因此改为：
 * <ol>
 *   <li>把 objective 切成子句；</li>
 *   <li>只要**有一个子句在下达处置指令**，就说明用户已经授权变更，不再判不对齐；</li>
 *   <li>处置词出现在<b>征询语</b>里（"清理<b>建议</b>"、"<b>要不要</b>封禁"）或出现在
 *       <b>查询动词的宾语</b>里（"<b>查看</b>回滚记录"）都不算授权——用户在问，不是在派活。</li>
 * </ol>
 *
 * <p>规则仍是常量表驱动的确定性判定，不依赖模型、不可被 Prompt 内容降级：
 * <ul>
 *   <li>只读工具永不拦截（读操作与任何意图都无害对齐）；</li>
 *   <li>写工具 + 咨询标记 + 无执行承诺 + 无处置子句 → R5 拒绝，命中
 *       {@code POLICY_INTENT_MISMATCH}；</li>
 *   <li>上游已拒绝的决策原样透传，本护栏只会收紧、不会放宽。</li>
 * </ul>
 *
 * <p>放宽的那部分风险并未落空：被放行的写操作照样受风险分级与人工审批门禁约束
 * （R3/R4 一律进审批队列），本护栏只是不再对"诊断+处置"复合意图做不可申诉的硬拒。
 */
@Service
public class IntentAlignmentGuard {

    public static final String POLICY_INTENT_MISMATCH = "POLICY_INTENT_MISMATCH";

    /** 咨询/查询意图标记：整段出现任一即触发对齐检查（触发而已，不等于判定不对齐）。 */
    private static final List<String> ADVISORY_MARKERS = List.of(
            "建议", "是否", "要不要", "查询", "查看", "检查", "列出", "分析", "看看",
            "评估", "统计", "advice", "recommend", "should ", "whether ", "list ", "check "
    );

    /** 明确执行承诺：用户清楚地要求现在就做这个变更。存在承诺时咨询标记被覆盖。 */
    private static final List<String> EXECUTION_COMMITMENTS = List.of(
            "执行", "立即", "马上", "确认", "现在就", "go ahead", "apply now", "execute"
    );

    /**
     * 征询标记：用户在问"要不要做/做不做得"，而不是在说"做什么"。
     * 带征询标记的子句永远不构成处置授权——"给我一份磁盘清理<b>建议</b>"里的"清理"
     * 是被征询的对象，不是被下达的指令。
     */
    private static final List<String> SOLICITATION_MARKERS = List.of(
            "建议", "意见", "要不要", "是否", "该不该", "需不需要", "能不能", "可不可以",
            "advice", "advise", "recommend", "suggestion", "should ", "whether "
    );

    /**
     * 查询动词：以它领起的子句是在"查"，其中出现的处置词只是查询的宾语
     * （"查看回滚记录"、"列出哪些可以清理"），不构成处置授权。
     */
    private static final List<String> QUERY_HEADS = List.of(
            "查看", "查询", "查一下", "查下", "检查", "看看", "看一下", "看下",
            "分析", "统计", "列出", "列一下", "罗列", "评估", "排查", "诊断", "了解",
            "告诉我", "给我看", "展示", "review", "check ", "list ", "show ", "analyze", "inspect"
    );

    /** 处置动词：出现即表示用户在要求改变系统状态。 */
    private static final List<String> DISPOSAL_VERBS = List.of(
            "清理", "清空", "清除", "删除", "移除", "重启", "启动", "拉起", "停止", "停掉",
            "关停", "重载", "重新加载", "封禁", "拉黑", "阻断", "屏蔽", "解封", "修复",
            "回滚", "还原", "杀掉", "杀死", "隔离", "截断", "归档", "扩容", "重置",
            "clean", "cleanup", "delete", "remove", "restart", "rollback", "block ", "kill"
    );

    /**
     * 子句分隔符。标点之外还切"并/并且/然后/接着/再/之后/and/then"，因为
     * 「检查磁盘并清理过期日志」这类复合指令并不带标点。切碎一点是安全的：
     * 判定只关心"有没有某个子句在下达处置指令"，多切只会让处置子句更容易被独立看见，
     * 不会凭空造出处置词。
     */
    private static final String CLAUSE_DELIMITERS =
            "[，,。．\\.！!？?；;：:、\\n]+|并且|然后|接着|之后|再|并| and | then ";

    private final ToolDefinitionProvider toolDefinitionProvider;

    public IntentAlignmentGuard(ToolDefinitionProvider toolDefinitionProvider) {
        this.toolDefinitionProvider = toolDefinitionProvider;
    }

    /** 从工具目录解析只读性后对齐；目录不认识的工具按写处理（fail-closed）。 */
    public PolicyDecision align(String userPrompt, String toolName, PolicyDecision upstream) {
        return align(userPrompt, toolName, isReadOnlyTool(toolName), upstream);
    }

    /**
     * 意图对齐判定。{@code readOnlyTool} 由调用方（DispatchGate 已持有 manifest 只读位）
     * 或本类的目录解析给出。
     */
    public PolicyDecision align(String userPrompt, String toolName, boolean readOnlyTool, PolicyDecision upstream) {
        if (upstream == null || upstream.denied() || readOnlyTool) {
            return upstream;
        }
        String prompt = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT).trim();
        if (prompt.isBlank()) {
            return upstream;
        }
        if (!containsAny(prompt, ADVISORY_MARKERS)
                || containsAny(prompt, EXECUTION_COMMITMENTS)
                || hasDisposalClause(prompt)) {
            return upstream;
        }
        List<PolicyHit> hits = new ArrayList<>(upstream.policyHits());
        hits.add(new PolicyHit(POLICY_INTENT_MISMATCH,
                "用户意图为咨询/查询，但请求的是写工具 " + toolName + "，意图与动作不一致", RiskCode.R5));
        return new PolicyDecision(RiskCode.R5, false, true, List.copyOf(hits), upstream.policyVersion());
    }

    /** 是否存在一个子句在真正下达处置指令（存在即视为用户已授权变更意图）。 */
    private boolean hasDisposalClause(String prompt) {
        for (String clause : prompt.split(CLAUSE_DELIMITERS)) {
            if (isDisposalClause(clause.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单子句判定：含处置动词，且既不是征询（"清理建议"），
     * 也不是查询动词的宾语（"查看回滚记录"——查询动词位置在处置词之前）。
     */
    private boolean isDisposalClause(String clause) {
        int disposalAt = firstIndexOfAny(clause, DISPOSAL_VERBS);
        if (disposalAt < 0) {
            return false;
        }
        if (containsAny(clause, SOLICITATION_MARKERS)) {
            return false;
        }
        int queryAt = firstIndexOfAny(clause, QUERY_HEADS);
        return queryAt < 0 || queryAt > disposalAt;
    }

    private boolean isReadOnlyTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        try {
            List<ToolDefinition> definitions = toolDefinitionProvider.describeTools(List.of(toolName));
            Optional<ToolDefinition> definition = definitions.isEmpty()
                    ? Optional.empty() : Optional.of(definitions.get(0));
            return definition.map(ToolDefinition::readOnly).orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean containsAny(String text, List<String> needles) {
        return firstIndexOfAny(text, needles) >= 0;
    }

    /** 任一 needle 的最小出现位置；都不出现返回 -1。 */
    private int firstIndexOfAny(String text, List<String> needles) {
        int best = -1;
        for (String needle : needles) {
            int at = text.indexOf(needle);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }
}

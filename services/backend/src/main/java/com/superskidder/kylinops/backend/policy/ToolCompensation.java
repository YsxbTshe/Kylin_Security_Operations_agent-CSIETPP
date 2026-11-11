package com.superskidder.kylinops.backend.policy;

/**
 * 写工具的补偿动作声明（ADR-001 §2.9.8）。
 *
 * <p>这份声明同时服务两个用途，因此必须由控制面权威给出，不接受端侧上报：
 * <ol>
 *   <li><b>回滚规划</b>：Agent 自主回滚时据此确定"撤销这一步要调哪个工具"，
 *       而不是靠模型推断 {@code unblock_ip} 是 {@code block_ip} 的逆操作；</li>
 *   <li><b>高危判定</b>：自动模式下"不可逆性"是拦截判据之一，
 *       声明为 {@link Kind#IRREVERSIBLE} 的动作不可由分类器放行（ADR-001 §2.10.4）。</li>
 * </ol>
 *
 * <p>如实声明"不可补偿"与声明逆操作同等重要：它让规划器在执行前就知道这一步跨过去回不来，
 * 从而要求更高级别审批，也让回滚规划遇到它时明确升级人工，而不是假装能撤销。
 */
public record ToolCompensation(
        Kind kind,
        /** 补偿动作的工具名；{@link Kind#IRREVERSIBLE} 时为空 */
        String compensatingTool,
        /** 补偿动作的参数来源 */
        ArgumentSource argumentSource,
        /** {@link ArgumentSource#FROM_RESULT} 时，从执行结果中取哪个字段 */
        String resultField,
        /** 执行补偿前必须满足的前置校验说明，避免回滚误伤任务之外的既有配置 */
        String precondition,
        String note
) {

    /**
     * 可补偿性分三级。**不可补偿与不可恢复必须区分**——把两者混同会导致风险等级膨胀：
     * {@code service.restart} 没有逆操作，但服务会重新起来；若因此升到 R4 强审批，
     * 而破坏性更强却可逆的 {@code service.stop} 反倒是 R3，等级体系就失去了意义。
     */
    public enum Kind {
        /** 存在确定的逆操作，可自动回滚 */
        REVERSIBLE,
        /** 没有逆操作，但系统仍保持可用状态（如重启：进程状态丢失，服务本身恢复） */
        NO_INVERSE,
        /** 没有逆操作且系统被留在退化状态，无法还原 */
        IRRECOVERABLE,
        /** 该工具本身就是补偿动作 */
        COMPENSATING
    }

    public enum ArgumentSource {
        /** 补偿动作使用与原动作相同的参数 */
        SAME_ARGS,
        /** 从原动作的执行结果中取参数（如 rollbackId） */
        FROM_RESULT,
        /** 无参数来源（不可逆或本身即补偿动作） */
        NONE
    }

    public static ToolCompensation reversible(String compensatingTool, String precondition) {
        return new ToolCompensation(Kind.REVERSIBLE, compensatingTool, ArgumentSource.SAME_ARGS, "", precondition, "");
    }

    public static ToolCompensation reversibleFromResult(String compensatingTool, String resultField, String precondition) {
        return new ToolCompensation(Kind.REVERSIBLE, compensatingTool, ArgumentSource.FROM_RESULT, resultField, precondition, "");
    }

    /** 无逆操作但系统仍可用：不升险，但回滚规划需知悉，且自动模式不得放行。 */
    public static ToolCompensation noInverse(String note) {
        return new ToolCompensation(Kind.NO_INVERSE, "", ArgumentSource.NONE, "", "", note);
    }

    /** 无逆操作且系统被留在退化状态：升险到 R4 强审批。 */
    public static ToolCompensation irrecoverable(String note) {
        return new ToolCompensation(Kind.IRRECOVERABLE, "", ArgumentSource.NONE, "", "", note);
    }

    public static ToolCompensation compensating(String note) {
        return new ToolCompensation(Kind.COMPENSATING, "", ArgumentSource.NONE, "", "", note);
    }

    /** 只读工具无副作用，无需补偿。 */
    public static ToolCompensation none() {
        return new ToolCompensation(Kind.COMPENSATING, "", ArgumentSource.NONE, "", "", "只读动作，无副作用");
    }

    /** 没有可自动执行的补偿动作。回滚规划遇到它必须升级人工，自动模式分类器不得放行。 */
    public boolean lacksCompensation() {
        return kind == Kind.NO_INVERSE || kind == Kind.IRRECOVERABLE;
    }

    /** 系统会被留在无法还原的退化状态——这才是升险到 R4 的条件。 */
    public boolean irrecoverable() {
        return kind == Kind.IRRECOVERABLE;
    }
}

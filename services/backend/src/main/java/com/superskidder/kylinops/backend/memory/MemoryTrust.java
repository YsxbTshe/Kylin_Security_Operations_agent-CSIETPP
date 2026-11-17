package com.superskidder.kylinops.backend.memory;

import java.util.Locale;

/**
 * 出处驱动的记忆可信度（ADR-001 §2.7）。
 *
 * 召回时不再假装记忆就是当前真相，而是按来源对相关性分数加权：经验证的更可信，
 * 来自失败/已回滚任务的结论更不可信，被更新版本取代或已过有效期的记忆强降权。
 *
 * 原则是「收紧不放宽」：认不出的结局信号取中性乘子 1.0，不把不确定当可信。
 * 抽成纯函数以便快速回归——它是记忆能否被采信的关键判据，值得独立钉住。
 */
public final class MemoryTrust {

    private MemoryTrust() {
    }

    public static double multiplier(boolean verified, String taskOutcome, boolean superseded, boolean expired) {
        double trust = 1.0D;
        if (verified) {
            trust *= 1.25D;
        }
        String outcome = taskOutcome == null ? "" : taskOutcome.toUpperCase(Locale.ROOT);
        switch (outcome) {
            case "SUCCESS" -> trust *= 1.1D;
            case "FAILED" -> trust *= 0.6D;
            case "ROLLED_BACK" -> trust *= 0.5D;
            default -> { /* 未知结局：中性，不加不减 */ }
        }
        if (superseded) {
            trust *= 0.3D; // 被更新版本取代：保留为历史但强降权
        }
        if (expired) {
            trust *= 0.2D; // 过了有效期：几乎不参与当前决策
        }
        return trust;
    }
}

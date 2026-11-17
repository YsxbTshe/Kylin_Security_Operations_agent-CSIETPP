package com.superskidder.kylinops.backend.incident;

import java.util.Locale;
import java.util.Set;

/**
 * 事件生命周期状态：
 * NEW → TRIAGED（发起研判/创建处置任务）→ CONTAINING（处置任务在执行）→ CONTAINED（阻断生效）
 *     → VERIFYING（只读验证）→ RESOLVED（解除阻断/恢复）→ CLOSED；FALSE_POSITIVE 为误报终态。
 * 终态不再接受处置流转（fail-closed），重复信号命中终态事件时按新事件处理。
 */
public enum IncidentStatus {
    NEW,
    TRIAGED,
    CONTAINING,
    CONTAINED,
    VERIFYING,
    RESOLVED,
    CLOSED,
    FALSE_POSITIVE;

    private static final Set<IncidentStatus> TERMINAL = Set.of(RESOLVED, CLOSED, FALSE_POSITIVE);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static IncidentStatus normalize(String value) {
        if (value == null || value.isBlank()) {
            return NEW;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return NEW;
        }
    }
}

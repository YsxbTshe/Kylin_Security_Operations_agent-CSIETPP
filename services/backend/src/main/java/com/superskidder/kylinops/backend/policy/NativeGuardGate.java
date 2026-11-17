package com.superskidder.kylinops.backend.policy;

import java.time.Duration;
import java.time.Instant;

/**
 * Native Guard 姿态的新鲜度判据（决赛计划 G：结构化 PolicyContext + 过期/UNKNOWN 拒绝）。
 *
 * 平台对高危写操作的放行，不能建立在「不知道目标主机安全姿态」之上。姿态从未上报
 * （UNKNOWN）或上报过旧（STALE）时，R3 以上动作必须 fail-safe 拒绝，而不是像旧逻辑那样
 * 静默放行。抽成纯函数以便快速回归——它决定「在看不清主机状态时是否仍敢下发高危写」。
 */
public final class NativeGuardGate {

    public enum Availability {
        /** 姿态已上报且在新鲜期内。 */
        FRESH,
        /** 从未上报（节点未接入姿态采集，或刚注册）。 */
        UNKNOWN,
        /** 上报过，但已超出新鲜期。 */
        STALE
    }

    private NativeGuardGate() {
    }

    public static Availability availability(String status, Instant checkedAt, Instant now, long maxAgeMinutes) {
        if (status == null || status.isBlank() || checkedAt == null) {
            return Availability.UNKNOWN;
        }
        if (maxAgeMinutes > 0 && Duration.between(checkedAt, now).compareTo(Duration.ofMinutes(maxAgeMinutes)) > 0) {
            return Availability.STALE;
        }
        return Availability.FRESH;
    }
}

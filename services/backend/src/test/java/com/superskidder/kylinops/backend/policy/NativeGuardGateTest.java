package com.superskidder.kylinops.backend.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.superskidder.kylinops.backend.policy.NativeGuardGate.Availability;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NativeGuardGateTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");

    @Test
    void freshReportedPostureIsAvailable() {
        assertThat(NativeGuardGate.availability("HEALTHY", NOW.minusSeconds(120), NOW, 30))
                .isEqualTo(Availability.FRESH);
    }

    @Test
    void neverReportedPostureIsUnknown() {
        assertThat(NativeGuardGate.availability("", null, NOW, 30)).isEqualTo(Availability.UNKNOWN);
        assertThat(NativeGuardGate.availability(null, NOW, NOW, 30)).isEqualTo(Availability.UNKNOWN);
        // 有状态但无观测时间 → 无法判断新鲜度 → 当作 UNKNOWN（收紧不放宽）。
        assertThat(NativeGuardGate.availability("HEALTHY", null, NOW, 30)).isEqualTo(Availability.UNKNOWN);
    }

    @Test
    void postureBeyondWindowIsStale() {
        assertThat(NativeGuardGate.availability("HEALTHY", NOW.minusSeconds(3600), NOW, 30))
                .isEqualTo(Availability.STALE);
    }

    @Test
    void zeroWindowDisablesStaleness() {
        // maxAge<=0 表示不做过期判断（仅 UNKNOWN 仍拦）。
        assertThat(NativeGuardGate.availability("HEALTHY", NOW.minusSeconds(999999), NOW, 0))
                .isEqualTo(Availability.FRESH);
    }
}

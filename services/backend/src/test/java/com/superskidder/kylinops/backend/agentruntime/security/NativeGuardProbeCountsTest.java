package com.superskidder.kylinops.backend.agentruntime.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NativeGuardProbeCountsTest {

    @Test
    void countsSupportedCriticalAndWarningItems() {
        NativeGuardProbeCounts counts = NativeGuardProbeCounts.fromJson("""
                {"overall":"critical","items":[
                  {"tool":"kylin.security.auditd_status","supported":true,"status":"inactive"},
                  {"tool":"kylin.security.selinux_status","supported":true,"status":"permissive"},
                  {"tool":"kylin.security.kysec_status","supported":false,"status":"unsupported"},
                  {"tool":"kylin.security.security_switch_status","supported":true,"status":"error"}
                ]}
                """);

        assertThat(counts.critical()).isEqualTo(2);
        assertThat(counts.warning()).isEqualTo(1);
    }

    @Test
    void blankOrInvalidJsonIsZero() {
        assertThat(NativeGuardProbeCounts.fromJson(null)).isEqualTo(NativeGuardProbeCounts.ZERO);
        assertThat(NativeGuardProbeCounts.fromJson("")).isEqualTo(NativeGuardProbeCounts.ZERO);
        assertThat(NativeGuardProbeCounts.fromJson("{")).isEqualTo(NativeGuardProbeCounts.ZERO);
        assertThat(NativeGuardProbeCounts.fromJson("{\"overall\":\"healthy\"}")).isEqualTo(NativeGuardProbeCounts.ZERO);
    }
}

package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Phase 7 迭代 7.1 验收基线 18.3（正确性与恢复）——SubAgent 深度/并发/总数硬门禁切片。
 * 落地链路的绕过向量（未声明 grant 字段提权、终态子 Agent 腾名额）见
 * RuntimeToolDispatcherTest 的 18.3 标注用例。
 */
class SubAgentLimitsTest {

    @Test
    void depthTwoIsAllowed() {
        SubAgentLimits.Decision decision = SubAgentLimits.evaluate(2, 0, 0);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.errorCode()).isNull();
    }

    @Test
    void depthThreeIsDenied() {
        SubAgentLimits.Decision decision = SubAgentLimits.evaluate(3, 0, 0);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo("SUB_AGENT_DEPTH_EXCEEDED");
    }

    @Test
    void fourthActiveSubAgentIsAllowed() {
        SubAgentLimits.Decision decision = SubAgentLimits.evaluate(1, 3, 3);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void fifthActiveSubAgentIsDenied() {
        SubAgentLimits.Decision decision = SubAgentLimits.evaluate(1, 4, 4);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo("SUB_AGENT_ACTIVE_LIMIT_EXCEEDED");
    }

    @Test
    void eighthTotalSubAgentIsAllowed() {
        SubAgentLimits.Decision decision = SubAgentLimits.evaluate(1, 0, 7);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void ninthTotalSubAgentIsDenied() {
        SubAgentLimits.Decision decision = SubAgentLimits.evaluate(1, 0, 8);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo("SUB_AGENT_TOTAL_LIMIT_EXCEEDED");
    }

    @Test
    void depthTakesPrecedenceOverCounts() {
        SubAgentLimits.Decision decision = SubAgentLimits.evaluate(3, 9, 9);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo("SUB_AGENT_DEPTH_EXCEEDED");
    }

    @Test
    void activeLimitTakesPrecedenceOverTotal() {
        SubAgentLimits.Decision decision = SubAgentLimits.evaluate(1, 4, 9);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo("SUB_AGENT_ACTIVE_LIMIT_EXCEEDED");
    }
}

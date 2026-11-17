package com.superskidder.kylinops.backend.agentruntime.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContextWindowPolicyTest {

    private ContextWindowPolicy policy(long windowTokens, double threshold) {
        return new ContextWindowPolicy(windowTokens, threshold);
    }

    private ContextWindowPolicy.Message message(long sequence, long tokens, boolean roundStart) {
        return new ContextWindowPolicy.Message(sequence, tokens, roundStart);
    }

    @Test
    void overBudgetCompressesOldestRoundsFirstAndNeverTouchesStableReservation() {
        ContextWindowPolicy policy = policy(200, 0.6);
        // 两个轮次：(seq1 assistant + seq2 tool) 与 (seq3 assistant + seq4 tool)
        List<ContextWindowPolicy.Message> messages = List.of(
                message(1, 40, true), message(2, 40, false),
                message(3, 40, true), message(4, 40, false));

        ContextWindowPolicy.TrimPlan plan = policy.plan(100, messages);

        // 总量 260 > 200：先整轮压掉最旧轮次 (seq1, seq2)，stable/dynamic 预留量 100 原样保留
        assertThat(plan.compressSequences()).containsExactly(1L, 2L);
        assertThat(plan.projectedTotalTokens()).isEqualTo(180);
    }

    @Test
    void exactlyAtWindowBudgetDoesNotTrim() {
        ContextWindowPolicy policy = policy(200, 1.0);
        List<ContextWindowPolicy.Message> messages = List.of(
                message(1, 50, true), message(2, 50, false));

        ContextWindowPolicy.TrimPlan plan = policy.plan(100, messages);

        assertThat(plan.compressSequences()).isEmpty();
        assertThat(plan.projectedTotalTokens()).isEqualTo(200);
    }

    @Test
    void messageSegmentOverSummaryThresholdTriggersCompressionEvenUnderWindow() {
        ContextWindowPolicy policy = policy(1_000, 0.6);
        List<ContextWindowPolicy.Message> messages = List.of(
                message(1, 300, true), message(2, 350, false),
                message(3, 50, true), message(4, 50, false));

        ContextWindowPolicy.TrimPlan plan = policy.plan(100, messages);

        // 消息段 750 > 600（窗口 60%），压最旧轮次后 100 <= 600 停止
        assertThat(plan.compressSequences()).containsExactly(1L, 2L);
        assertThat(plan.projectedTotalTokens()).isEqualTo(200);
    }

    @Test
    void underBudgetYieldsEmptyPlan() {
        ContextWindowPolicy policy = policy(32_000, 0.6);

        ContextWindowPolicy.TrimPlan plan = policy.plan(500, List.of(message(1, 100, true)));

        assertThat(plan.compressSequences()).isEmpty();
    }

    @Test
    void roundGroupingTreatsRoundStartAsBoundary() {
        List<List<ContextWindowPolicy.Message>> rounds = ContextWindowPolicy.rounds(List.of(
                message(1, 10, true), message(2, 10, false), message(3, 10, true)));

        assertThat(rounds).hasSize(2);
        assertThat(rounds.get(0)).hasSize(2);
        assertThat(rounds.get(1)).hasSize(1);
    }
}

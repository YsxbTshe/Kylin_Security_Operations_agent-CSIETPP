package com.superskidder.kylinops.backend.agentruntime.context;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型窗口预算：stable 段永驻不参与裁剪（其 token 只作为预留量传入），
 * dynamic/消息段超出窗口时从最旧轮次开始整轮压缩。恰好等于上限不算超出。
 */
@Component
public class ContextWindowPolicy {

    private final long windowTokens;
    private final double summaryThreshold;

    public ContextWindowPolicy(
            @Value("${app.context.window-tokens:32000}") long windowTokens,
            @Value("${app.context.summary-threshold:0.6}") double summaryThreshold
    ) {
        if (windowTokens < 1) {
            throw new IllegalArgumentException("app.context.window-tokens must be >= 1");
        }
        if (summaryThreshold <= 0.0 || summaryThreshold > 1.0) {
            throw new IllegalArgumentException("app.context.summary-threshold must be in (0, 1]");
        }
        this.windowTokens = windowTokens;
        this.summaryThreshold = summaryThreshold;
    }

    public long windowTokens() {
        return windowTokens;
    }

    public long summaryThresholdTokens() {
        return (long) (windowTokens * summaryThreshold);
    }

    /** roundStart=true 的消息开启一个新轮次（assistant 工具调用消息）。 */
    public record Message(long sequenceNo, long tokens, boolean roundStart) {}

    public record TrimPlan(List<Long> compressSequences, long projectedTotalTokens) {
        public TrimPlan {
            compressSequences = List.copyOf(compressSequences);
        }
    }

    /**
     * 计算需要压缩的最旧轮次。触发条件：总量超过窗口，或消息段超过窗口 * summaryThreshold。
     * 压缩按轮次整体进行（roundStart 消息及其后续非 roundStart 消息），从最旧开始，
     * 直到回到预算内或无轮次可压；stable/dynamic 预留量永不裁剪。
     */
    public TrimPlan plan(long stableAndDynamicTokens, List<Message> messages) {
        long threshold = summaryThresholdTokens();
        long messageTokens = 0;
        for (Message message : messages) {
            messageTokens += message.tokens();
        }
        long total = stableAndDynamicTokens + messageTokens;
        if (total <= windowTokens && messageTokens <= threshold) {
            return new TrimPlan(List.of(), total);
        }
        List<Long> compress = new ArrayList<>();
        for (List<Message> round : rounds(messages)) {
            if (total <= windowTokens && messageTokens <= threshold) {
                break;
            }
            for (Message message : round) {
                compress.add(message.sequenceNo());
                total -= message.tokens();
                messageTokens -= message.tokens();
            }
        }
        return new TrimPlan(compress, total);
    }

    static List<List<Message>> rounds(List<Message> messages) {
        List<List<Message>> rounds = new ArrayList<>();
        List<Message> current = null;
        for (Message message : messages) {
            if (message.roundStart() || current == null) {
                current = new ArrayList<>();
                rounds.add(current);
            }
            current.add(message);
        }
        return rounds;
    }
}

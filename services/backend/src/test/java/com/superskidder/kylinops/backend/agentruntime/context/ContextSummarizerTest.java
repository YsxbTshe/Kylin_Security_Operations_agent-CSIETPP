package com.superskidder.kylinops.backend.agentruntime.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextSummarizerTest {

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ContextSummarizer summarizer = new ContextSummarizer(new ObjectMapper(), metrics);

    private Map<String, Object> assistantCall(String callId, String toolName) {
        return Map.of("role", "assistant", "content", "",
                "tool_calls", List.of(Map.of("id", callId, "type", "function",
                        "function", Map.of("name", toolName, "arguments", "{\"service\":\"nginx\"}"))));
    }

    private Map<String, Object> toolResult(String callId, String toolName, String contentJson) {
        return Map.of("role", "tool", "tool_call_id", callId, "name", toolName, "content", contentJson);
    }

    @Test
    void summaryContainsToolNamesResultStatusAndKeyNumbers() {
        String summary = summarizer.summarize(null, List.of(
                assistantCall("call-1", "kylin.service.status"),
                toolResult("call-1", "kylin.service.status",
                        "{\"status\":\"SUCCEEDED\",\"service\":\"nginx\",\"activeCount\":3}")));

        assertThat(summary)
                .contains("kylin.service.status")
                .contains("SUCCEEDED")
                .contains("activeCount=3")
                .startsWith("[compressed-history]");
    }

    @Test
    void summaryMarksFailuresWithErrorStatus() {
        String summary = summarizer.summarize(null, List.of(
                assistantCall("call-2", "kylin.service.restart"),
                toolResult("call-2", "kylin.service.restart",
                        "{\"error\":\"approval_required\",\"retryable\":false}")));

        assertThat(summary).contains("kylin.service.restart").contains("FAILED(approval_required)");
    }

    @Test
    void mergesPriorSummaryDeterministically() {
        String prior = "[compressed-history] 1) kylin.service.status -> SUCCEEDED";
        String first = summarizer.summarize(prior, List.of(
                assistantCall("call-3", "kylin.disk.usage"),
                toolResult("call-3", "kylin.disk.usage", "{\"state\":\"OK\",\"usedPercent\":81}")));
        String second = summarizer.summarize(prior, List.of(
                assistantCall("call-3", "kylin.disk.usage"),
                toolResult("call-3", "kylin.disk.usage", "{\"state\":\"OK\",\"usedPercent\":81}")));

        assertThat(first).isEqualTo(second).contains(prior).contains("kylin.disk.usage").contains("OK");
    }

    @Test
    void summarySegmentIsDynamicSystemSegment() {
        PromptSegment segment = summarizer.summarySegment("text");

        assertThat(segment.name()).isEqualTo(ContextSummarizer.SEGMENT_NAME);
        assertThat(segment.role()).isEqualTo("system");
        assertThat(segment.stable()).isFalse();
        assertThat(segment.content()).isEqualTo("text");
    }

    @Test
    void recordCompressionExposesCountersWithoutNewEndpoint() {
        summarizer.recordCompression(120);
        summarizer.recordCompression(30);

        assertThat(summarizer.compressionCount()).isEqualTo(2.0);
        assertThat(summarizer.tokensSaved()).isEqualTo(150.0);
        assertThat(metrics.get("kylin.agent.context.summary.compressions").counter().count()).isEqualTo(2.0);
        assertThat(metrics.get("kylin.agent.context.summary.tokens.saved").counter().count()).isEqualTo(150.0);
    }
}

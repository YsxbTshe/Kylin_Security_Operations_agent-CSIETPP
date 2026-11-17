package com.superskidder.kylinops.backend.agentruntime.model;

import com.superskidder.kylinops.backend.agentruntime.context.CompiledContext;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ModelGatewayRequest(
        CompiledContext context,
        List<LlmTool> tools,
        String reasoningLevel,
        Duration timeout,
        List<Map<String, Object>> extraMessages
) {
    public ModelGatewayRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
        reasoningLevel = reasoningLevel == null ? "OFF" : reasoningLevel.trim().toUpperCase();
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        extraMessages = extraMessages == null ? List.of() : List.copyOf(extraMessages);
    }

    public ModelGatewayRequest(CompiledContext context, List<LlmTool> tools, String reasoningLevel, Duration timeout) {
        this(context, tools, reasoningLevel, timeout, List.of());
    }

    public List<Map<String, Object>> allMessages() {
        if (extraMessages.isEmpty()) {
            return context.messages();
        }
        var combined = new ArrayList<Map<String, Object>>(context.messages());
        combined.addAll(extraMessages);
        return List.copyOf(combined);
    }
}

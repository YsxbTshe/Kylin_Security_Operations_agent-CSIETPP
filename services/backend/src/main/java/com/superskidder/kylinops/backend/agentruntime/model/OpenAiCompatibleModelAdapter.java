package com.superskidder.kylinops.backend.agentruntime.model;

import com.superskidder.kylinops.backend.llm.LlmCompletionService;
import com.superskidder.kylinops.backend.llm.LlmToolCompletion;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleModelAdapter implements ModelProviderAdapter {

    private final LlmCompletionService completionService;

    public OpenAiCompatibleModelAdapter(LlmCompletionService completionService) {
        this.completionService = completionService;
    }

    @Override
    public boolean supports(String providerType) {
        return "OPENAI_COMPATIBLE".equalsIgnoreCase(providerType) || "CUSTOM".equalsIgnoreCase(providerType);
    }

    @Override
    public ModelGatewayResult complete(RoutedModel model, ModelGatewayRequest request) {
        long started = System.nanoTime();
        LlmToolCompletion completion = completionService.completeWithToolMessages(
                model.config().id(), request.allMessages(), request.tools(), request.reasoningLevel());
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        return new ModelGatewayResult(completion.content(), completion.toolCalls(),
                request.context().tokenEstimate(), estimateOutput(completion), 0, 0, elapsed, elapsed, 0);
    }

    protected long estimateOutput(LlmToolCompletion completion) {
        int length = completion.content().length();
        for (var call : completion.toolCalls()) {
            length += call.name().length() + call.argumentsJson().length();
        }
        return Math.max(1, (length + 3L) / 4L);
    }
}

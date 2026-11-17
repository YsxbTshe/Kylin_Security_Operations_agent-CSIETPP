package com.superskidder.kylinops.backend.agentruntime.model;

import com.superskidder.kylinops.backend.llm.LlmCompletionService;
import com.superskidder.kylinops.backend.llm.LlmToolCompletion;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekModelAdapter implements ModelProviderAdapter {

    private final LlmCompletionService completionService;

    public DeepSeekModelAdapter(LlmCompletionService completionService) {
        this.completionService = completionService;
    }

    @Override
    public boolean supports(String providerType) {
        return "DEEPSEEK".equalsIgnoreCase(providerType);
    }

    @Override
    public ModelGatewayResult complete(RoutedModel model, ModelGatewayRequest request) {
        long started = System.nanoTime();
        LlmToolCompletion completion = completionService.completeWithToolMessages(
                model.config().id(), request.allMessages(), request.tools(), request.reasoningLevel());
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        int outputLength = completion.content().length() + completion.toolCalls().stream()
                .mapToInt(call -> call.name().length() + call.argumentsJson().length()).sum();
        return new ModelGatewayResult(completion.content(), completion.toolCalls(), request.context().tokenEstimate(),
                Math.max(1, (outputLength + 3L) / 4L), 0, 0, elapsed, elapsed, 0);
    }
}

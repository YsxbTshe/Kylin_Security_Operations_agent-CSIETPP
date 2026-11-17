package com.superskidder.kylinops.backend.agentruntime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.superskidder.kylinops.backend.llm.LlmModelConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelGatewayTest {

    @Test
    void selectsExactlyOneProviderAdapter() {
        ModelProviderAdapter openAi = mock(ModelProviderAdapter.class);
        ModelProviderAdapter deepSeek = mock(ModelProviderAdapter.class);
        ModelProviderAdapter anthropic = mock(ModelProviderAdapter.class);
        when(openAi.supports("ANTHROPIC")).thenReturn(false);
        when(deepSeek.supports("ANTHROPIC")).thenReturn(false);
        when(anthropic.supports("ANTHROPIC")).thenReturn(true);
        ModelGatewayResult expected = new ModelGatewayResult("", List.of(), 1, 1, 0, 0, 1, 1, 0);
        RoutedModel routed = new RoutedModel("profile", new LlmModelConfig("id", "ANTHROPIC", "group",
                "https://api.anthropic.com/v1", "key", "claude", true, 1000, 0,
                "OFF", "OFF", "test", LocalDateTime.now()), "OFF", "EXPLICIT_BREAKPOINT", "test", "digest");
        ModelGatewayRequest request = mock(ModelGatewayRequest.class);
        when(anthropic.complete(routed, request)).thenReturn(expected);

        assertThat(new ModelGateway(List.of(openAi, deepSeek, anthropic)).complete(routed, request)).isSameAs(expected);
        verify(anthropic).complete(routed, request);
        verify(openAi, never()).complete(routed, request);
        verify(deepSeek, never()).complete(routed, request);
    }
}

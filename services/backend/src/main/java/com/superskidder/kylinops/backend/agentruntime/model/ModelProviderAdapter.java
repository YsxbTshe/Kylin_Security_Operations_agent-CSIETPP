package com.superskidder.kylinops.backend.agentruntime.model;

public interface ModelProviderAdapter {
    boolean supports(String providerType);

    ModelGatewayResult complete(RoutedModel model, ModelGatewayRequest request);
}

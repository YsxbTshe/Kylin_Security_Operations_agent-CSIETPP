package com.superskidder.kylinops.backend.agentruntime.model;

import com.superskidder.kylinops.backend.common.AppException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ModelGateway {

    private final List<ModelProviderAdapter> adapters;

    public ModelGateway(List<ModelProviderAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public ModelGatewayResult complete(RoutedModel model, ModelGatewayRequest request) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(model.config().providerType()))
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST,
                        "Unsupported model provider: " + model.config().providerType()))
                .complete(model, request);
    }
}

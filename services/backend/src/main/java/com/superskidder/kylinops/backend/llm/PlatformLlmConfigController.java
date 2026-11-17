package com.superskidder.kylinops.backend.llm;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/platform/llm-config")
public class PlatformLlmConfigController {

    private final PlatformLlmConfigService configService;
    private final LlmCompletionService completionService;
    private final AccessControlService accessControlService;

    public PlatformLlmConfigController(
            PlatformLlmConfigService configService,
            LlmCompletionService completionService,
            AccessControlService accessControlService
    ) {
        this.configService = configService;
        this.completionService = completionService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public ApiResponse<List<LlmModelConfigView>> listConfigs() {
        if (!accessControlService.currentUser().permissions().contains("platform:configure")) {
            accessControlService.requirePermission("assistant:use");
        }
        return ApiResponse.success(configService.listViews());
    }

    @PostMapping
    public ApiResponse<LlmModelConfigView> createConfig(@Valid @RequestBody CreateLlmModelConfigRequest request) {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(configService.create(accessControlService.currentUser().username(), request));
    }

    @PutMapping("/{configId}")
    public ApiResponse<LlmModelConfigView> updateConfig(
            @PathVariable String configId,
            @Valid @RequestBody UpdateLlmModelConfigRequest request
    ) {
        accessControlService.requirePermission("platform:configure");
        validateConfigId(configId);
        return ApiResponse.success(configService.update(accessControlService.currentUser().username(), configId, request));
    }

    @DeleteMapping("/{configId}")
    public ApiResponse<Boolean> deleteConfig(@PathVariable String configId) {
        accessControlService.requirePermission("platform:configure");
        validateConfigId(configId);
        configService.delete(configId);
        return ApiResponse.success(true);
    }

    @PostMapping("/{configId}/test")
    public ApiResponse<LlmConfigTestResponse> testConfig(@PathVariable String configId) {
        accessControlService.requirePermission("platform:configure");
        validateConfigId(configId);
        return ApiResponse.success(completionService.testConfig(configId));
    }

    private void validateConfigId(String configId) {
        if (configId == null || configId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "configId 不能为空");
        }
        if (configId.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "configId 长度不能超过64");
        }
    }
}

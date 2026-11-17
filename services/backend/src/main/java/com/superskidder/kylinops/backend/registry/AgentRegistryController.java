package com.superskidder.kylinops.backend.registry;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 专家注册表 / 知识域 / Agent IAM 接口（决赛计划 R）。读需 registry:read，维护需 registry:write。
 * /authorize 暴露 Agent 能力授权判定（fail-closed），供演示「越封套即拒」。
 */
@RestController
@RequestMapping("/api/v1/registry")
public class AgentRegistryController {

    private final AgentRegistryService registryService;
    private final AccessControlService accessControlService;

    public AgentRegistryController(AgentRegistryService registryService, AccessControlService accessControlService) {
        this.registryService = registryService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/agents")
    public ApiResponse<List<AgentRegistryEntry>> listAgents() {
        accessControlService.requirePermission("registry:read");
        return ApiResponse.success(registryService.listAgents(accessControlService.currentOrganizationId()));
    }

    @GetMapping("/agents/{agentKey}")
    public ApiResponse<AgentRegistryEntry> getAgent(@PathVariable String agentKey) {
        accessControlService.requirePermission("registry:read");
        return ApiResponse.success(registryService.getAgent(accessControlService.currentOrganizationId(), agentKey));
    }

    @PostMapping("/agents")
    public ApiResponse<AgentRegistryEntry> upsert(@Valid @RequestBody AgentUpsertRequest request) {
        accessControlService.requirePermission("registry:write");
        return ApiResponse.success(registryService.upsert(accessControlService.currentOrganizationId(), request));
    }

    @PostMapping("/agents/{agentKey}/enabled")
    public ApiResponse<AgentRegistryEntry> setEnabled(@PathVariable String agentKey,
                                                      @RequestBody Map<String, Boolean> body) {
        accessControlService.requirePermission("registry:write");
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        return ApiResponse.success(registryService.setEnabled(accessControlService.currentOrganizationId(), agentKey, enabled));
    }

    @DeleteMapping("/agents/{agentKey}")
    public ApiResponse<Void> delete(@PathVariable String agentKey) {
        accessControlService.requirePermission("registry:write");
        registryService.delete(accessControlService.currentOrganizationId(), agentKey);
        return ApiResponse.success(null);
    }

    /** Agent 能力授权判定：给定专家、工具与风险，返回是否放行（fail-closed）。 */
    @GetMapping("/agents/{agentKey}/authorize")
    public ApiResponse<AgentAuthorization> authorize(@PathVariable String agentKey,
                                                     @RequestParam String tool,
                                                     @RequestParam(required = false, defaultValue = "R0") String risk) {
        accessControlService.requirePermission("registry:read");
        return ApiResponse.success(
                registryService.authorize(accessControlService.currentOrganizationId(), agentKey, tool, risk));
    }

    @GetMapping("/domains")
    public ApiResponse<List<KnowledgeDomain>> listDomains() {
        accessControlService.requirePermission("registry:read");
        return ApiResponse.success(registryService.listDomains(accessControlService.currentOrganizationId()));
    }

    @PostMapping("/domains")
    public ApiResponse<KnowledgeDomain> upsertDomain(@RequestBody Map<String, String> body) {
        accessControlService.requirePermission("registry:write");
        return ApiResponse.success(registryService.upsertDomain(
                accessControlService.currentOrganizationId(),
                body.get("code"), body.get("name"), body.get("description")));
    }
}

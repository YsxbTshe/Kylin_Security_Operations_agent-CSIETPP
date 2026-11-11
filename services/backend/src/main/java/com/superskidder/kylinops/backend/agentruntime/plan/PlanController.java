package com.superskidder.kylinops.backend.agentruntime.plan;

import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/agent-runs/{runId}/plan")
public class PlanController {

    private final PlanService planService;
    private final AccessControlService accessControl;

    public PlanController(PlanService planService, AccessControlService accessControl) {
        this.planService = planService;
        this.accessControl = accessControl;
    }

    @GetMapping
    public ApiResponse<List<PlanService.PlanVersionView>> list(@PathVariable String runId) {
        accessControl.requirePermission("agent:read");
        return ApiResponse.success(planService.listVersions(accessControl.currentOrganizationId(), runId));
    }

    // 路径正则限定为数字：否则 "canvas" 会被 Spring 尝试绑定到 int version 而 400。
    @GetMapping("/{version:\\d+}")
    public ApiResponse<PlanService.PlanVersionDetail> get(@PathVariable String runId, @PathVariable int version) {
        accessControl.requirePermission("agent:read");
        return ApiResponse.success(planService.getVersion(accessControl.currentOrganizationId(), runId, version));
    }

    /**
     * Plan Canvas 数据源：最新计划版本解析成阶段/步骤，并与真实 tool_invocation 对账。
     * 只读，权限与查看计划一致。
     */
    @GetMapping("/canvas")
    public ApiResponse<PlanStructure.PlanCanvasView> canvas(@PathVariable String runId) {
        accessControl.requirePermission("agent:read");
        return ApiResponse.success(planService.getCanvas(accessControl.currentOrganizationId(), runId));
    }

    @PostMapping
    public ApiResponse<PlanService.PlanVersionView> create(
            @PathVariable String runId, @Valid @RequestBody CreatePlanRequest request) {
        accessControl.requirePermission("assistant:use");
        return ApiResponse.success(planService.createPlanVersion(
                accessControl.currentOrganizationId(), runId,
                request.contentMarkdown(), accessControl.currentUser().userId()));
    }

    @PostMapping("/amend")
    public ApiResponse<PlanService.PlanVersionView> amend(
            @PathVariable String runId, @Valid @RequestBody AmendPlanRequest request) {
        accessControl.requirePermission("assistant:use");
        return ApiResponse.success(planService.amendPlan(
                accessControl.currentOrganizationId(), runId, request.baseVersion(),
                request.contentMarkdown(), accessControl.currentUser().userId()));
    }

    @PostMapping("/{version}/launch")
    public ApiResponse<AgentRuntimeStore.CreatedRun> launch(
            @PathVariable String runId, @PathVariable int version,
            @Valid @RequestBody LaunchPlanRunRequest request) {
        accessControl.requirePermission("assistant:use");
        String tenantId = accessControl.currentOrganizationId();
        PlanService.PlanVersionDetail plan = planService.getVersion(tenantId, runId, version);
        return ApiResponse.success(planService.launchRunFromPlan(tenantId, plan.id(),
                new PlanService.LaunchSpec(request.objective(), request.executionMode(),
                        request.targetScope(), request.grant(), request.budget(),
                        request.modelProfileId(), request.reasoningLevel()),
                accessControl.currentUser().userId()));
    }

    public record CreatePlanRequest(
            @NotBlank @Size(max = 65_536) String contentMarkdown
    ) {}

    public record AmendPlanRequest(
            @NotNull @Positive Integer baseVersion,
            @NotBlank @Size(max = 65_536) String contentMarkdown
    ) {}

    public record LaunchPlanRunRequest(
            @NotBlank @Size(max = 4_000) String objective,
            @Pattern(regexp = "(?i)PLAN|CAUTIOUS|AUTO") String executionMode,
            Map<String, Object> targetScope,
            Map<String, Object> grant,
            Map<String, Object> budget,
            @Size(max = 64) String modelProfileId,
            @Pattern(regexp = "(?i)OFF|LOW|MEDIUM|HIGH") String reasoningLevel
    ) {}
}

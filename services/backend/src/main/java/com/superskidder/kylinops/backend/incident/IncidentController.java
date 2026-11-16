package com.superskidder.kylinops.backend.incident;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全事件响应闭环 HTTP 接口。读操作需 incident:read，处置需 incident:respond。
 * 旧 SafeOps 处置任务链路（triage → host task）已随 Java 侧旧执行链路一并下线。
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final AccessControlService accessControlService;

    public IncidentController(IncidentService incidentService,
                             AccessControlService accessControlService) {
        this.incidentService = incidentService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public ApiResponse<List<IncidentSummary>> list(@RequestParam(required = false) String status) {
        accessControlService.requirePermission("incident:read");
        return ApiResponse.success(incidentService.list(accessControlService.currentOrganizationId(), status));
    }

    @GetMapping("/metrics")
    public ApiResponse<IncidentMetrics> metrics() {
        accessControlService.requirePermission("incident:read");
        return ApiResponse.success(incidentService.metrics(accessControlService.currentOrganizationId()));
    }

    @GetMapping("/runbooks")
    public ApiResponse<List<IncidentRunbookSummary>> runbooks() {
        accessControlService.requirePermission("incident:read");
        return ApiResponse.success(incidentService.listRunbooks(accessControlService.currentOrganizationId()));
    }

    @GetMapping("/{incidentId}")
    public ApiResponse<IncidentDetail> get(@PathVariable String incidentId) {
        accessControlService.requirePermission("incident:read");
        return ApiResponse.success(incidentService.get(accessControlService.currentOrganizationId(), incidentId));
    }

    @PostMapping("/intake")
    public ApiResponse<IncidentIntakeResult> intake(@Valid @RequestBody IncidentIntakeRequest request) {
        accessControlService.requirePermission("incident:respond");
        return ApiResponse.success(incidentService.intake(accessControlService.currentOrganizationId(), request));
    }

    /** 从告警创建事件（告警→事件闭环入口，幂等）。 */
    @PostMapping("/from-alert/{alertId}")
    public ApiResponse<IncidentIntakeResult> intakeFromAlert(@PathVariable long alertId) {
        accessControlService.requirePermission("incident:respond");
        return ApiResponse.success(
                incidentService.intakeFromAlert(accessControlService.currentOrganizationId(), alertId));
    }

    /**
     * 发起研判：置 TRIAGED、记 acknowledged_at，并返回处置话术与目标主机。
     * 前端据此创建 Agent 任务，再用 link-task 回填 —— 事件编排与执行门禁保持解耦。
     */
    @PostMapping("/{incidentId}/triage")
    public ApiResponse<IncidentTriagePlan> triage(@PathVariable String incidentId) {
        accessControlService.requirePermission("incident:respond");
        return ApiResponse.success(
                incidentService.beginTriage(accessControlService.currentOrganizationId(), incidentId));
    }

    /** 回填处置任务（Agent runId）并进入 CONTAINING。CONTAINED 由遏制巡检按 run 终态推进。 */
    @PostMapping("/{incidentId}/link-task")
    public ApiResponse<IncidentDetail> linkTask(@PathVariable String incidentId,
                                                @RequestBody(required = false) Map<String, String> body) {
        accessControlService.requirePermission("incident:respond");
        String taskId = body == null ? null : body.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "taskId 不能为空");
        }
        String organizationId = accessControlService.currentOrganizationId();
        incidentService.linkTask(organizationId, incidentId, taskId.trim());
        return ApiResponse.success(incidentService.get(organizationId, incidentId));
    }

    @PostMapping("/{incidentId}/resolve")
    public ApiResponse<IncidentDetail> resolve(@PathVariable String incidentId) {
        accessControlService.requirePermission("incident:respond");
        return ApiResponse.success(incidentService.resolve(accessControlService.currentOrganizationId(), incidentId));
    }

    @PostMapping("/{incidentId}/false-positive")
    public ApiResponse<IncidentDetail> falsePositive(@PathVariable String incidentId,
                                                     @RequestBody(required = false) Map<String, String> body) {
        accessControlService.requirePermission("incident:respond");
        String note = body == null ? null : body.get("note");
        return ApiResponse.success(
                incidentService.markFalsePositive(accessControlService.currentOrganizationId(), incidentId, note));
    }
}

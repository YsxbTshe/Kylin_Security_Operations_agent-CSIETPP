package com.superskidder.kylinops.backend.dashboard;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final AccessControlService accessControlService;

    public DashboardController(DashboardService dashboardService, AccessControlService accessControlService) {
        this.dashboardService = dashboardService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary() {
        accessControlService.requirePermission("overview:read");
        return ApiResponse.success(dashboardService.summary(accessControlService.currentOrganizationId()));
    }
}

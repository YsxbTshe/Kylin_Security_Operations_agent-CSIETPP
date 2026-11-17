package com.superskidder.kylinops.backend.alert;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alertService;
    private final AccessControlService accessControlService;

    public AlertController(AlertService alertService, AccessControlService accessControlService) {
        this.alertService = alertService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public ApiResponse<List<AlertSummary>> listAlerts() {
        accessControlService.requirePermission("alert:read");
        return ApiResponse.success(alertService.listAlerts(accessControlService.currentOrganizationId()));
    }
}

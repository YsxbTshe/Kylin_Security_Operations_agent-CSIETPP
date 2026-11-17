package com.superskidder.kylinops.backend.audit;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audits")
public class AuditController {

    private final AuditService auditService;
    private final AccessControlService accessControlService;

    public AuditController(AuditService auditService, AccessControlService accessControlService) {
        this.auditService = auditService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public ApiResponse<List<AuditRecord>> listAudits(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String fromTime,
            @RequestParam(required = false) String toTime,
            @RequestParam(defaultValue = "100") int limit
    ) {
        accessControlService.requirePermission("audit:read");
        AuditQueryValidator.validate(operator, action, target, fromTime, toTime, limit);
        return ApiResponse.success(auditService.listAudits(
                accessControlService.currentOrganizationId(),
                operator,
                action,
                target,
                fromTime,
                toTime,
                limit
        ));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportAudits(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String fromTime,
            @RequestParam(required = false) String toTime,
            @RequestParam(defaultValue = "500") int limit
    ) {
        accessControlService.requirePermission("audit:read");
        AuditQueryValidator.validate(operator, action, target, fromTime, toTime, limit);
        String organizationId = accessControlService.currentOrganizationId();
        auditService.record(
                organizationId,
                accessControlService.currentUser().username(),
                "AUDIT_EXPORT",
                "/api/v1/audits/export?limit=" + limit
        );
        byte[] content = auditService.exportCsv(
                organizationId,
                operator,
                action,
                target,
                fromTime,
                toTime,
                limit
        ).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("platform-audits.csv", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(content);
    }
}

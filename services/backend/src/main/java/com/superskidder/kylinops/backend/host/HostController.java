package com.superskidder.kylinops.backend.host;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.grpc.HostAgentGrpcServer;
import com.superskidder.kylinops.backend.policy.ToolDefinitionProvider;
import com.superskidder.kylinops.backend.security.AccessControlService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/hosts")
public class HostController {

    private final HostService hostService;
    private final ToolDefinitionProvider toolDefinitionProvider;
    private final AccessControlService accessControlService;
    private final HostAgentGrpcServer grpcServer;

    public HostController(
            HostService hostService,
            ToolDefinitionProvider toolDefinitionProvider,
            AccessControlService accessControlService,
            HostAgentGrpcServer grpcServer
    ) {
        this.hostService = hostService;
        this.toolDefinitionProvider = toolDefinitionProvider;
        this.accessControlService = accessControlService;
        this.grpcServer = grpcServer;
    }

    @GetMapping
    public ApiResponse<List<HostSummary>> listHosts() {
        accessControlService.requirePermission("host:read");
        return ApiResponse.success(hostService.listHosts(accessControlService.currentOrganizationId()));
    }

    @GetMapping("/{hostname}")
    public ApiResponse<HostDetail> getHostDetail(@PathVariable String hostname) {
        accessControlService.requirePermission("host:read");
        validateHostname(hostname);
        String organizationId = accessControlService.currentOrganizationId();
        HostSummary host = hostService.getHost(organizationId, hostname);
        return ApiResponse.success(new HostDetail(
                host.hostname(),
                host.ip(),
                host.status(),
                host.osVersion(),
                host.cpuUsage(),
                host.memoryUsage(),
                host.diskUsage(),
                host.loadAverage(),
                host.uptimeText(),
                host.nodeId(),
                host.clusterName(),
                host.agentVersion(),
                host.supportedTools(),
                toolDefinitionProvider.describeTools(host.supportedTools()),
                host.lastHeartbeatAt(),
                host.grpcConnected(),
                host.grpcConnectedAt(),
                host.certSubject(),
                host.certExpiresAt(),
                host.certStatus(),
                List.of()
        ));
    }

    @PostMapping("/{hostname}/certificate/revoke")
    public ApiResponse<Void> revokeCertificate(
            @PathVariable String hostname,
            @RequestParam(defaultValue = "manual revocation") String reason
    ) {
        accessControlService.requirePermission("platform:configure");
        validateHostname(hostname);
        String organizationId = accessControlService.currentOrganizationId();
        // HostService.revokeCertificate 已把 grpc_connected 置为 false；reload 会重读 CRL 并
        // 重建控制端口上的一切连接（含新链 V2 ControlStream），被吊销节点随即断开且无法重连。
        hostService.revokeCertificate(organizationId, hostname, reason);
        try {
            grpcServer.reload();
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Host Agent gRPC 服务重载失败");
        }
        return ApiResponse.success(null);
    }

    @PostMapping("/{hostname}/certificate/reissue-requests")
    public ApiResponse<HostCertificateReissueRequest> requestCertificateReissue(
            @PathVariable String hostname,
            @RequestParam(defaultValue = "certificate re-issue requested") String reason
    ) {
        accessControlService.requirePermission("platform:configure");
        validateHostname(hostname);
        return ApiResponse.success(hostService.requestCertificateReissue(
                accessControlService.currentOrganizationId(),
                hostname,
                accessControlService.currentUser().username(),
                reason
        ));
    }

    @PostMapping("/certificate-reissue-requests/{requestId}/approve")
    public ApiResponse<HostCertificateReissueApproval> approveCertificateReissue(
            @PathVariable String requestId,
            @RequestParam(defaultValue = "approved") String note
    ) {
        accessControlService.requirePermission("platform:configure");
        validateCertificateReissueRequestId(requestId);
        return ApiResponse.success(hostService.approveCertificateReissue(
                accessControlService.currentOrganizationId(),
                requestId,
                accessControlService.currentUser().username(),
                note
        ));
    }

    @PostMapping("/certificate-reissue-requests/{requestId}/reject")
    public ApiResponse<HostCertificateReissueRequest> rejectCertificateReissue(
            @PathVariable String requestId,
            @RequestParam(defaultValue = "rejected") String note
    ) {
        accessControlService.requirePermission("platform:configure");
        validateCertificateReissueRequestId(requestId);
        return ApiResponse.success(hostService.rejectCertificateReissue(
                accessControlService.currentOrganizationId(),
                requestId,
                accessControlService.currentUser().username(),
                note
        ));
    }

    @GetMapping("/{hostname}/certificate")
    public ApiResponse<HostCertificateBundle> getCertificateBundle(@PathVariable String hostname) {
        accessControlService.requirePermission("host:read");
        validateHostname(hostname);
        return ApiResponse.success(hostService.certificateBundle(accessControlService.currentOrganizationId(), hostname));
    }

    private void validateCertificateReissueRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId 不能为空");
        }
        if (requestId.length() > 64 || !requestId.startsWith("cert-reissue-")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId 不合法");
        }
    }

    private void validateHostname(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hostname 不能为空");
        }
        if (hostname.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hostname 长度不能超过128");
        }
    }
}

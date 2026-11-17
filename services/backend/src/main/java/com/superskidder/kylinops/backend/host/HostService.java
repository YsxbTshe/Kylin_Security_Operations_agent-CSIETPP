package com.superskidder.kylinops.backend.host;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.cert.HostAgentCertificateService;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HostService {

    private final JdbcTemplate jdbcTemplate;
    private final long offlineTimeoutSeconds;
    private final ObjectMapper objectMapper;
    private final HostAgentCertificateService certificateService;
    private final long reissueTokenTtlSeconds;

    public HostService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.host-agent.offline-timeout-seconds:180}") long offlineTimeoutSeconds,
            ObjectMapper objectMapper,
            HostAgentCertificateService certificateService,
            @Value("${app.host-agent.grpc.reissue-token-ttl-seconds:600}") long reissueTokenTtlSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.offlineTimeoutSeconds = offlineTimeoutSeconds;
        this.objectMapper = objectMapper;
        this.certificateService = certificateService;
        this.reissueTokenTtlSeconds = reissueTokenTtlSeconds <= 0 ? 600 : reissueTokenTtlSeconds;
    }

    public List<HostSummary> listHosts(String organizationId) {
        List<HostRow> rows = jdbcTemplate.query(
                """
                select h.hostname, h.ip, h.status, h.os_version, h.cpu_usage, h.memory_usage, h.disk_usage, h.load_average, h.uptime_text,
                       h.node_id, h.cluster_name, h.agent_version, h.supported_tools_json, h.tool_manifest_json, h.last_heartbeat_at,
                       h.grpc_connected, h.grpc_connected_at, h.cert_subject, h.cert_expires_at, h.cert_status,
                       -- 姿态与探针明细均来自心跳写入的会话表。
                       s.native_guard_status, s.native_guard_observed_at as native_guard_checked_at,
                       s.native_guard_critical_count, s.native_guard_warning_count, s.native_guard_json,
                       h.node_exporter_status, h.node_exporter_checked_at, h.node_exporter_cpu_usage_percent,
                       h.node_exporter_memory_usage_percent, h.node_exporter_filesystem_usage_percent,
                       h.node_exporter_load1, h.node_exporter_json,
                       o.id as organization_id, o.name as organization_name
                from app_managed_host h
                join app_organization o on o.id = h.organization_id
                left join agent_runtime_node_session s
                  on s.tenant_id = h.organization_id and s.node_id = h.node_id
                where h.organization_id = ?
                order by h.hostname
                """,
                (resultSet, rowNum) -> new HostRow(
                        resultSet.getString("hostname"),
                        resultSet.getString("ip"),
                        resultSet.getString("status"),
                        resultSet.getString("os_version"),
                        resultSet.getString("cpu_usage"),
                        resultSet.getString("memory_usage"),
                        resultSet.getString("disk_usage"),
                        resultSet.getString("load_average"),
                        resultSet.getString("uptime_text"),
                        resultSet.getString("node_id"),
                        resultSet.getString("cluster_name"),
                        resultSet.getString("agent_version"),
                        resultSet.getString("supported_tools_json"),
                        resultSet.getString("tool_manifest_json"),
                        resultSet.getTimestamp("last_heartbeat_at"),
                        resultSet.getBoolean("grpc_connected"),
                        resultSet.getTimestamp("grpc_connected_at"),
                        resultSet.getString("cert_subject"),
                        resultSet.getTimestamp("cert_expires_at"),
                        resultSet.getString("cert_status"),
                        resultSet.getString("native_guard_status"),
                        resultSet.getTimestamp("native_guard_checked_at"),
                        resultSet.getInt("native_guard_critical_count"),
                        resultSet.getInt("native_guard_warning_count"),
                        resultSet.getString("native_guard_json"),
                        resultSet.getString("node_exporter_status"),
                        resultSet.getTimestamp("node_exporter_checked_at"),
                        resultSet.getObject("node_exporter_cpu_usage_percent", Double.class),
                        resultSet.getObject("node_exporter_memory_usage_percent", Double.class),
                        resultSet.getObject("node_exporter_filesystem_usage_percent", Double.class),
                        resultSet.getObject("node_exporter_load1", Double.class),
                        resultSet.getString("node_exporter_json"),
                        resultSet.getString("organization_id"),
                        resultSet.getString("organization_name")
                ),
                organizationId);
        List<HostSummary> hosts = new ArrayList<>(rows.size());
        for (HostRow row : rows) {
            hosts.add(new HostSummary(
                    row.hostname(),
                    emptyToDash(row.ip()),
                    effectiveStatus(row.status(), row.lastHeartbeatAt()),
                    row.osVersion(),
                    emptyToDash(row.cpuUsage()),
                    emptyToDash(row.memoryUsage()),
                    emptyToDash(row.diskUsage()),
                    emptyToDash(row.loadAverage()),
                    emptyToDash(row.uptimeText()),
                    emptyToDash(row.nodeId()),
                    emptyToDash(row.clusterName()),
                    emptyToDash(row.agentVersion()),
                    parseTools(row.supportedToolsJson()),
                    format(row.lastHeartbeatAt()),
                    row.grpcConnected(),
                    format(row.grpcConnectedAt()),
                    emptyToDash(row.certSubject()),
                    format(row.certExpiresAt()),
                    certificateStatus(row.certStatus(), row.certExpiresAt()),
                    nativeGuardStatus(row.nativeGuardStatus(), row.nativeGuardCheckedAt()),
                    format(row.nativeGuardCheckedAt()),
                    row.nativeGuardCriticalCount(),
                    row.nativeGuardWarningCount(),
                    row.nativeGuardJson() == null ? "" : row.nativeGuardJson(),
                    nodeExporterStatus(row.nodeExporterStatus(), row.nodeExporterCheckedAt()),
                    format(row.nodeExporterCheckedAt()),
                    row.nodeExporterCpuUsagePercent(),
                    row.nodeExporterMemoryUsagePercent(),
                    row.nodeExporterFilesystemUsagePercent(),
                    row.nodeExporterLoad1(),
                    row.nodeExporterJson() == null ? "" : row.nodeExporterJson(),
                    row.organizationId(),
                    row.organizationName()
            ));
        }
        return hosts;
    }

    public HostSummary getHost(String organizationId, String hostname) {
        return listHosts(organizationId).stream()
                .filter(item -> item.hostname().equals(hostname))
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "未找到主机: " + hostname));
    }

    /**
     * enroll 签发证书后立即落库。ControlStream 的 mTLS 拦截器要求
     * {@code app_managed_host} 已有 {@code cert_status=ACTIVE} 且序列号匹配的行，
     * 而写入方原先只在被该拦截器拒绝的那条流里才会被调用——全新库因此永远纳管不了节点。
     */
    public boolean recordEnrollment(
            String organizationId,
            String nodeId,
            String hostname,
            String certSubject,
            String certSerialNumber,
            Instant certExpiresAt
    ) {
        if (!organizationExists(organizationId)) {
            return false;
        }
        String resolvedNodeId = firstNonBlank(nodeId, hostname);
        String resolvedHostname = firstNonBlank(hostname, resolvedNodeId);
        String serial = blankToDefault(certSerialNumber, "").trim().toLowerCase();
        if (serial.isBlank()) {
            throw new IllegalArgumentException("cert serial number is required to enroll a host");
        }
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expires = certExpiresAt == null ? null : Timestamp.from(certExpiresAt);
        ManagedHostIdentity host = findManagedHost(organizationId, resolvedNodeId, resolvedHostname);
        if (host == null) {
            jdbcTemplate.update("""
                    insert into app_managed_host (
                        organization_id, hostname, ip, status, os_version, cpu_usage, memory_usage, disk_usage,
                        load_average, uptime_text, node_id, cluster_name, agent_version, supported_tools_json,
                        tool_manifest_json, last_heartbeat_at, grpc_connected, cert_subject, cert_expires_at,
                        cert_serial_number, cert_status, cert_issued_at, cert_revoked_at, cert_revocation_reason
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    organizationId,
                    resolvedHostname,
                    "",
                    "OFFLINE",
                    "unknown",
                    "-",
                    "-",
                    "-",
                    "-",
                    "-",
                    resolvedNodeId,
                    null,
                    null,
                    "[]",
                    null,
                    null,
                    false,
                    normalize(certSubject),
                    expires,
                    serial,
                    "ACTIVE",
                    now,
                    null,
                    null
            );
            return true;
        }
        jdbcTemplate.update("""
                update app_managed_host
                set node_id = ?, hostname = ?, cert_subject = ?, cert_expires_at = ?,
                    cert_serial_number = ?, cert_status = 'ACTIVE', cert_issued_at = ?,
                    cert_revoked_at = null, cert_revocation_reason = null
                where id = ?
                """,
                resolvedNodeId,
                resolvedHostname,
                normalize(certSubject),
                expires,
                serial,
                now,
                host.id()
        );
        return true;
    }

    public boolean syncRegistration(
            String organizationId,
            String nodeId,
            String clusterName,
            String hostname,
            String ipAddress,
            String osVersion,
            String cpuUsage,
            String memoryUsage,
            String diskUsage,
            String loadAverage,
            String uptimeText,
            List<String> supportedTools,
            String agentVersion,
            String toolManifestJson
    ) {
        if (!organizationExists(organizationId)) {
            return false;
        }

        ManagedHostIdentity host = findManagedHost(organizationId, nodeId, hostname);
        Timestamp now = Timestamp.from(Instant.now());
        String resolvedNodeId = firstNonBlank(nodeId, hostname);
        String resolvedHostname = firstNonBlank(hostname, resolvedNodeId);
        String resolvedOsVersion = blankToDefault(osVersion, "unknown");
        jdbcTemplate.update("""
                insert into app_managed_host (
                    organization_id, hostname, ip, status, os_version, cpu_usage, memory_usage, disk_usage,
                    load_average, uptime_text, node_id, cluster_name, agent_version, supported_tools_json, tool_manifest_json, last_heartbeat_at
                )
                select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                where ? is null
                """,
                organizationId,
                resolvedHostname,
                blankToDefault(ipAddress, ""),
                "ONLINE",
                resolvedOsVersion,
                blankToDefault(cpuUsage, "-"),
                blankToDefault(memoryUsage, "-"),
                blankToDefault(diskUsage, "-"),
                blankToDefault(loadAverage, "-"),
                blankToDefault(uptimeText, "-"),
                resolvedNodeId,
                normalize(clusterName),
                normalize(agentVersion),
                writeTools(supportedTools),
                normalize(toolManifestJson),
                now,
                host == null ? null : host.id()
        );

        if (host != null) {
            jdbcTemplate.update("""
                update app_managed_host
                set node_id = ?, cluster_name = ?, hostname = ?, ip = ?, os_version = ?, cpu_usage = ?, memory_usage = ?,
                    disk_usage = ?, load_average = ?, uptime_text = ?, agent_version = ?, supported_tools_json = ?, tool_manifest_json = ?,
                    status = ?, last_heartbeat_at = ?
                where id = ?
                """,
                    resolvedNodeId,
                    normalize(clusterName),
                    resolvedHostname,
                    blankToDefault(ipAddress, ""),
                    resolvedOsVersion,
                    blankToDefault(cpuUsage, "-"),
                    blankToDefault(memoryUsage, "-"),
                    blankToDefault(diskUsage, "-"),
                    blankToDefault(loadAverage, "-"),
                    blankToDefault(uptimeText, "-"),
                    normalize(agentVersion),
                    writeTools(supportedTools),
                    normalize(toolManifestJson),
                    "ONLINE",
                    now,
                    host.id()
            );
        }
        refreshNodeExporterFromHeartbeat(organizationId, resolvedNodeId, cpuUsage, memoryUsage, diskUsage, loadAverage, now);
        return true;
    }

    public boolean syncHeartbeat(
            String organizationId,
            String nodeId,
            String hostname,
            String ipAddress,
            String osVersion,
            String cpuUsage,
            String memoryUsage,
            String diskUsage,
            String loadAverage,
            String uptimeText,
            List<String> supportedTools,
            long sentUnixTime
    ) {
        ManagedHostIdentity host = findManagedHost(organizationId, nodeId, null);
        if (host == null) {
            return false;
        }

        jdbcTemplate.update("""
                update app_managed_host
                set status = ?, last_heartbeat_at = ?, hostname = coalesce(?, hostname), ip = coalesce(?, ip),
                    os_version = coalesce(?, os_version), cpu_usage = coalesce(?, cpu_usage),
                    memory_usage = coalesce(?, memory_usage), disk_usage = coalesce(?, disk_usage),
                    load_average = coalesce(?, load_average), uptime_text = coalesce(?, uptime_text),
                    supported_tools_json = coalesce(?, supported_tools_json),
                    tool_manifest_json = coalesce(?, tool_manifest_json)
                where id = ?
                """,
                "ONLINE",
                Timestamp.from(Instant.now()),
                normalize(hostname),
                normalize(ipAddress),
                normalize(osVersion),
                normalize(cpuUsage),
                normalize(memoryUsage),
                normalize(diskUsage),
                normalize(loadAverage),
                normalize(uptimeText),
                supportedTools == null ? null : writeTools(supportedTools),
                null,
                host.id()
        );
        refreshNodeExporterFromHeartbeat(organizationId, nodeId,
                cpuUsage, memoryUsage, diskUsage, loadAverage, Timestamp.from(Instant.now()));
        return true;
    }

    public HostCertificateReissueRequest requestCertificateReissue(String organizationId, String hostname, String requestedBy, String reason) {
        HostSummary host = getHost(organizationId, hostname);
        Integer pending = jdbcTemplate.queryForObject("""
                select count(1)
                from host_agent_certificate_reissue_request
                where organization_id = ? and node_id = ? and status in ('PENDING', 'APPROVED')
                """, Integer.class, organizationId, host.nodeId());
        if (pending != null && pending > 0) {
            throw new AppException(HttpStatus.CONFLICT, "该主机已有待处理的证书 re-issue 请求");
        }
        String requestId = "cert-reissue-" + UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                insert into host_agent_certificate_reissue_request (
                    id, organization_id, node_id, hostname, requested_by, reason, status, requested_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                requestId,
                organizationId,
                host.nodeId(),
                host.hostname(),
                requestedBy == null || requestedBy.isBlank() ? "system" : requestedBy.trim(),
                normalize(reason),
                "PENDING",
                now,
                now
        );
        return certificateReissueRequest(organizationId, requestId);
    }

    public HostCertificateReissueApproval approveCertificateReissue(String organizationId, String requestId, String approver, String note) {
        HostCertificateReissueRequest request = pendingCertificateReissueRequest(organizationId, requestId);
        String token = "reissue-token-" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expiresAt = Timestamp.from(Instant.now().plusSeconds(reissueTokenTtlSeconds));
        jdbcTemplate.update("""
                update host_agent_certificate_reissue_request
                set status = 'APPROVED', decided_by = ?, decided_at = ?, decision_note = ?,
                    reissue_token_hash = ?, reissue_token_expires_at = ?, updated_at = ?
                where id = ? and organization_id = ? and status = 'PENDING'
                """,
                approver == null || approver.isBlank() ? "system" : approver.trim(),
                now,
                normalize(note),
                Sm3.hex(token),
                expiresAt,
                now,
                request.requestId(),
                organizationId
        );
        return new HostCertificateReissueApproval(certificateReissueRequest(organizationId, request.requestId()), token);
    }

    public HostCertificateReissueRequest rejectCertificateReissue(String organizationId, String requestId, String approver, String note) {
        HostCertificateReissueRequest request = pendingCertificateReissueRequest(organizationId, requestId);
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                update host_agent_certificate_reissue_request
                set status = 'REJECTED', decided_by = ?, decided_at = ?, decision_note = ?, updated_at = ?
                where id = ? and organization_id = ? and status = 'PENDING'
                """,
                approver == null || approver.isBlank() ? "system" : approver.trim(),
                now,
                note == null || note.isBlank() ? "Rejected by " + (approver == null || approver.isBlank() ? "system" : approver.trim()) : note.trim(),
                now,
                request.requestId(),
                organizationId
        );
        return certificateReissueRequest(organizationId, request.requestId());
    }

    public boolean consumeCertificateReissueToken(String organizationId, String nodeId, String token) {
        String normalizedToken = normalize(token);
        if (normalize(organizationId) == null || normalize(nodeId) == null || normalizedToken == null) {
            return false;
        }
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbcTemplate.update("""
                update host_agent_certificate_reissue_request
                set status = 'CONSUMED', reissue_token_consumed_at = ?, updated_at = ?
                where organization_id = ? and node_id = ? and status = 'APPROVED'
                  and reissue_token_hash = ? and reissue_token_consumed_at is null
                  and reissue_token_expires_at > ?
                """,
                now,
                now,
                organizationId,
                nodeId,
                Sm3.hex(normalizedToken),
                now
        );
        return updated > 0;
    }

    private HostCertificateReissueRequest pendingCertificateReissueRequest(String organizationId, String requestId) {
        HostCertificateReissueRequest request = certificateReissueRequest(organizationId, requestId);
        if (!"PENDING".equals(request.status())) {
            throw new AppException(HttpStatus.CONFLICT, "证书 re-issue 请求不是待审批状态");
        }
        return request;
    }

    private HostCertificateReissueRequest certificateReissueRequest(String organizationId, String requestId) {
        return jdbcTemplate.query("""
                select id, hostname, node_id, status, requested_by, requested_at, reason,
                       decided_by, decided_at, decision_note, reissue_token_expires_at, reissue_token_consumed_at
                from host_agent_certificate_reissue_request
                where organization_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new AppException(HttpStatus.NOT_FOUND, "未找到证书 re-issue 请求: " + requestId);
            }
            return new HostCertificateReissueRequest(
                    rs.getString("id"),
                    rs.getString("hostname"),
                    rs.getString("node_id"),
                    rs.getString("status"),
                    rs.getString("requested_by"),
                    format(rs.getTimestamp("requested_at")),
                    rs.getString("reason"),
                    rs.getString("decided_by"),
                    format(rs.getTimestamp("decided_at")),
                    rs.getString("decision_note"),
                    format(rs.getTimestamp("reissue_token_expires_at")),
                    format(rs.getTimestamp("reissue_token_consumed_at"))
            );
        }, organizationId, requestId);
    }

    public String revokeCertificate(String organizationId, String hostname, String reason) {
        HostSummary host = getHost(organizationId, hostname);
        String normalizedReason = normalize(reason);
        int updated = jdbcTemplate.update("""
                update app_managed_host
                set cert_status = ?, cert_revoked_at = ?, cert_revocation_reason = ?,
                    grpc_connected = ?, status = ?
                where organization_id = ? and node_id = ?
                """,
                "REVOKED",
                Timestamp.from(Instant.now()),
                normalizedReason == null ? "manual revocation" : normalizedReason,
                false,
                "OFFLINE",
                organizationId,
                host.nodeId()
        );
        if (updated == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "未找到主机证书: " + hostname);
        }
        return host.nodeId();
    }

    public HostCertificateBundle certificateBundle(String organizationId, String hostname) {
        HostSummary host = getHost(organizationId, hostname);
        List<HostAgentCertificateService.RevokedCertificate> revoked = jdbcTemplate.query("""
                select cert_serial_number, cert_revoked_at, cert_revocation_reason
                from app_managed_host
                where organization_id = ?
                  and cert_status = 'REVOKED'
                  and cert_serial_number is not null and cert_serial_number <> ''
                order by cert_revoked_at desc nulls last, id asc
                """, (rs, rowNum) -> new HostAgentCertificateService.RevokedCertificate(
                rs.getString("cert_serial_number"),
                rs.getTimestamp("cert_revoked_at") == null ? null : rs.getTimestamp("cert_revoked_at").toInstant(),
                rs.getString("cert_revocation_reason")
        ), organizationId);
        try {
            return new HostCertificateBundle(
                    host.hostname(),
                    host.nodeId(),
                    host.certSubject(),
                    host.certExpiresAt(),
                    host.certStatus(),
                    certificateService.caCertificatePem(),
                    certificateService.revocationListPem(revoked)
            );
        } catch (Exception exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "生成主机证书生命周期信息失败");
        }
    }

    private ManagedHostIdentity findManagedHost(String organizationId, String nodeId, String hostname) {
        String normalizedNodeId = normalize(nodeId);
        if (normalizedNodeId != null) {
            List<ManagedHostIdentity> matchedByNodeId = jdbcTemplate.query("""
                    select id, hostname
                    from app_managed_host
                    where organization_id = ? and node_id = ?
                    order by id asc
                    limit 1
                    """,
                    (resultSet, rowNum) -> new ManagedHostIdentity(
                            resultSet.getLong("id"),
                            resultSet.getString("hostname")
                    ),
                    organizationId,
                    normalizedNodeId
            );
            if (!matchedByNodeId.isEmpty()) {
                return matchedByNodeId.get(0);
            }

            List<ManagedHostIdentity> matchedByHostname = jdbcTemplate.query("""
                    select id, hostname
                    from app_managed_host
                    where organization_id = ? and hostname = ?
                    order by id asc
                    limit 1
                    """,
                    (resultSet, rowNum) -> new ManagedHostIdentity(
                            resultSet.getLong("id"),
                            resultSet.getString("hostname")
                    ),
                    organizationId,
                    normalizedNodeId
            );
            if (!matchedByHostname.isEmpty()) {
                return matchedByHostname.get(0);
            }
        }

        String normalizedHostname = normalize(hostname);
        if (normalizedHostname == null) {
            return null;
        }

        List<ManagedHostIdentity> matchedByHostname = jdbcTemplate.query("""
                select id, hostname
                from app_managed_host
                where organization_id = ? and hostname = ?
                order by id asc
                limit 1
                """,
                (resultSet, rowNum) -> new ManagedHostIdentity(
                        resultSet.getLong("id"),
                        resultSet.getString("hostname")
                ),
                organizationId,
                normalizedHostname
        );
        return matchedByHostname.isEmpty() ? null : matchedByHostname.get(0);
    }

    private boolean organizationExists(String organizationId) {
        String normalizedOrganizationId = normalize(organizationId);
        if (normalizedOrganizationId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from app_organization where id = ?",
                Integer.class,
                normalizedOrganizationId
        );
        return count != null && count > 0;
    }

    private String effectiveStatus(String storedStatus, Timestamp lastHeartbeatAt) {
        if (lastHeartbeatAt == null) {
            return storedStatus;
        }
        long ageSeconds = Instant.now().getEpochSecond() - lastHeartbeatAt.toInstant().getEpochSecond();
        if (ageSeconds > offlineTimeoutSeconds) {
            return "OFFLINE";
        }
        return storedStatus;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String firstNonBlank(String primary, String secondary) {
        String normalizedPrimary = normalize(primary);
        if (normalizedPrimary != null) {
            return normalizedPrimary;
        }
        String normalizedSecondary = normalize(secondary);
        if (normalizedSecondary != null) {
            return normalizedSecondary;
        }
        return "unknown-host";
    }

    private String emptyToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String format(Timestamp timestamp) {
        if (timestamp == null) {
            return "";
        }
        return timestamp.toLocalDateTime().toString().replace('T', ' ');
    }

    private String certificateStatus(String storedStatus, Timestamp expiresAt) {
        if ("REVOKED".equalsIgnoreCase(storedStatus)) {
            return "REVOKED";
        }
        if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) {
            return "EXPIRED";
        }
        return storedStatus == null || storedStatus.isBlank() ? "UNKNOWN" : storedStatus;
    }

    private String nativeGuardStatus(String storedStatus, Timestamp checkedAt) {
        if (checkedAt == null || storedStatus == null || storedStatus.isBlank()) {
            return "UNKNOWN";
        }
        if (Instant.now().getEpochSecond() - checkedAt.toInstant().getEpochSecond() > offlineTimeoutSeconds) {
            return "STALE";
        }
        return storedStatus.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String nodeExporterStatus(String storedStatus, Timestamp checkedAt) {
        if (checkedAt == null || storedStatus == null || storedStatus.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = storedStatus.trim().toUpperCase(java.util.Locale.ROOT);
        if ("DISABLED".equals(normalized)) {
            return normalized;
        }
        if (Instant.now().getEpochSecond() - checkedAt.toInstant().getEpochSecond() > offlineTimeoutSeconds) {
            return "STALE";
        }
        return normalized;
    }

    private List<String> parseTools(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String writeTools(List<String> supportedTools) {
        try {
            return objectMapper.writeValueAsString(supportedTools == null ? List.of() : supportedTools);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private void refreshNodeExporterFromHeartbeat(
            String organizationId,
            String nodeId,
            String cpuUsage,
            String memoryUsage,
            String diskUsage,
            String loadAverage,
            Timestamp checkedAt
    ) {
        Double cpu = parseUsagePercent(cpuUsage);
        Double memory = parseUsagePercent(memoryUsage);
        Double disk = parseUsagePercent(diskUsage);
        Double load1 = parseLoad1(loadAverage);
        boolean hasSample = cpu != null || memory != null || disk != null || load1 != null;
        jdbcTemplate.update("""
                update app_managed_host
                set node_exporter_status = ?,
                    node_exporter_checked_at = ?,
                    node_exporter_cpu_usage_percent = ?,
                    node_exporter_memory_usage_percent = ?,
                    node_exporter_filesystem_usage_percent = ?,
                    node_exporter_load1 = ?
                where organization_id = ? and node_id = ?
                """,
                hasSample ? "UP" : "UNKNOWN",
                checkedAt,
                cpu,
                memory,
                disk,
                load1,
                organizationId,
                nodeId);
    }

    private Double parseUsagePercent(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw.trim())) {
            return null;
        }
        String normalized = raw.trim().replace("%", "");
        try {
            double value = Double.parseDouble(normalized);
            if (value < 0) {
                return 0.0;
            }
            return value > 100 ? 100.0 : value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseLoad1(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw.trim())) {
            return null;
        }
        String first = raw.trim().split("[,\\s]+")[0];
        try {
            return Double.parseDouble(first);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record HostRow(
            String hostname,
            String ip,
            String status,
            String osVersion,
            String cpuUsage,
            String memoryUsage,
            String diskUsage,
            String loadAverage,
            String uptimeText,
            String nodeId,
            String clusterName,
            String agentVersion,
            String supportedToolsJson,
            String toolManifestJson,
            Timestamp lastHeartbeatAt,
            boolean grpcConnected,
            Timestamp grpcConnectedAt,
            String certSubject,
            Timestamp certExpiresAt,
            String certStatus,
            String nativeGuardStatus,
            Timestamp nativeGuardCheckedAt,
            int nativeGuardCriticalCount,
            int nativeGuardWarningCount,
            String nativeGuardJson,
            String nodeExporterStatus,
            Timestamp nodeExporterCheckedAt,
            Double nodeExporterCpuUsagePercent,
            Double nodeExporterMemoryUsagePercent,
            Double nodeExporterFilesystemUsagePercent,
            Double nodeExporterLoad1,
            String nodeExporterJson,
            String organizationId,
            String organizationName
    ) {
    }

    private record ManagedHostIdentity(long id, String hostname) {
    }
}

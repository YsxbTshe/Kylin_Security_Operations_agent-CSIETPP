package com.superskidder.kylinops.backend.agentruntime.connector;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** connector_invocation 审计：不含凭据与完整响应体，只存目标与请求摘要。 */
@Component
public class ConnectorInvocationAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConnectorInvocationAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String tenantId, String runId, String connectorId, String target,
                       String requestDigest, Integer responseStatus, long durationMs) {
        jdbcTemplate.update("""
                insert into connector_invocation (
                    id, tenant_id, run_id, connector_id, target,
                    request_digest, response_status, duration_ms
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), tenantId, runId, connectorId, target,
                requestDigest, responseStatus, durationMs);
    }
}

package com.superskidder.kylinops.backend.agentruntime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 招募测试共用的 H2 数据装配：父 run、根/子 agent_instance、LeasedAgentRun 组装。 */
final class AgentTestFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentTestFixtures() {
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String insertRun(
            JdbcTemplate jdbc,
            String status,
            Map<String, Object> grant,
            Map<String, Object> budget,
            Map<String, Object> usage
    ) {
        String runId = "run-" + UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into agent_run (
                    id, tenant_id, human_principal_id, objective, execution_mode,
                    target_scope_json, target_scope_digest, grant_version, grant_snapshot_json,
                    status, input_revision, budget_json, usage_json, lease_owner, created_at, updated_at
                ) values (?, ?, ?, 'fixture objective', 'CAUTIOUS', ?, ?, 1, ?, ?, 0, ?, ?, ?, ?, ?)
                """, runId, AgentTestDatabase.TENANT_ID, AgentTestDatabase.USER_ID,
                json(Map.of("nodeId", "node-1")), "digest-target", json(grant), status,
                json(budget), json(usage), "worker-test", now, now);
        return runId;
    }

    static String insertInstance(JdbcTemplate jdbc, String runId, String parentId, int depth, String status) {
        String instanceId = "agent-" + UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into agent_instance (
                    id, run_id, tenant_id, parent_agent_instance_id, instance_name, avatar_seed,
                    definition_snapshot_json, objective, depth, status, reasoning_level,
                    grant_snapshot_json, grant_digest, budget_json, usage_json, created_at, updated_at
                ) values (?, ?, ?, ?, 'Fixture Agent', 'fixture', ?, 'fixture objective', ?, ?, 'OFF',
                    '{}', 'digest-grant', '{}', '{}', ?, ?)
                """, instanceId, runId, AgentTestDatabase.TENANT_ID, parentId,
                json(Map.of("template", "fixture")), depth, status, now, now);
        return instanceId;
    }

    static LeasedAgentRun leasedRun(
            String runId,
            String rootInstanceId,
            Map<String, Object> grant,
            Map<String, Object> budget
    ) {
        return new LeasedAgentRun(runId, AgentTestDatabase.TENANT_ID, AgentTestDatabase.USER_ID,
                rootInstanceId, "fixture objective", "CAUTIOUS",
                json(Map.of("nodeId", "node-1")), json(grant), json(budget),
                0L, null, "OFF");
    }
}

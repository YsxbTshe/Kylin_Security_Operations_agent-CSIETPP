package com.superskidder.kylinops.backend.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class AgentRuntimeMigrationTest {

    @Test
    void agentRuntimeMigrationsBuildOnExistingHistoryAndEnforceRunInvariants() throws Exception {
        String url = "jdbc:h2:mem:agent-runtime-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        try (Connection keeper = DriverManager.getConnection(url, "sa", "")) {
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(keeper, true);
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load();
            // Phase 7 迭代 7.4：旧迁移链压缩为 V1__baseline.sql。
            // 之后的增量：V2 Native Guard 会话列、V3 感知只读工具目录种子、
            // V4 写工具 output schema、V5/V6 agent ABAC、V7 初始改密标记、
            // V8 审批决策专用权限 agent:approve、V9 下线无消费者的 outbox_event、
            // V10 agent_instance.registry_agent_key（专家注册表接入下发路径的身份桥）；
            // V11 RUNTIME 工具的 tool_package_version 行（缺了它审批分支会撞外键）；
            // V12 Native Guard 探针明细。
            assertEquals(12, flyway.migrate().migrationsExecuted);

            try (Statement statement = keeper.createStatement()) {
                statement.executeUpdate("""
                    insert into agent_run (
                        id, tenant_id, human_principal_id, objective, execution_mode,
                        target_scope_json, target_scope_digest, grant_snapshot_json,
                        status, budget_json, usage_json, created_at, updated_at
                    ) values (
                        '00000000-0000-4000-8000-000000000001', 'org-alpha', 'user-admin', 'check node', 'PLAN',
                        '{}', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '{}',
                        'QUEUED', '{}', '{}', current_timestamp, current_timestamp
                    )
                    """);

                assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    insert into agent_run (
                        id, tenant_id, human_principal_id, objective, execution_mode,
                        target_scope_json, target_scope_digest, grant_snapshot_json,
                        status, budget_json, usage_json, created_at, updated_at
                    ) values (
                        '00000000-0000-4000-8000-000000000002', 'org-alpha', 'user-admin', 'check node', 'PLAN',
                        '{}', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', '{}',
                        'WAITING', '{}', '{}', current_timestamp, current_timestamp
                    )
                    """));

                assertEquals(1, statement.executeUpdate("""
                        update tool_package_version set status = 'APPROVED'
                        where id = 'toolpkgver-service-status-v1'
                        """));
            }
        }
    }
}

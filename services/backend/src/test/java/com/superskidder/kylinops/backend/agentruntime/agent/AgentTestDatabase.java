package com.superskidder.kylinops.backend.agentruntime.agent;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * SubAgent 招募测试的最小数据库引导（沿用 artifact/vfs/skill 包的模式）：
 * H2（PostgreSQL 兼容模式）上直接执行 Phase 7 迭代 7.4 压缩后的新基线
 * db/migration/V1__baseline.sql（包含全部存活表与种子数据）。
 *
 * 注意：使用 SingleConnectionDataSource 让建表与测试 DML 共用同一会话。
 * H2 2.4.240 存在回归（h2database#4291，2.4.241 前未发布修复）：
 * 由已关闭会话创建的 CHECK 约束在新会话上求值时会误报
 * "Check constraint invalid ... The database has been closed"。
 */
final class AgentTestDatabase {

    static final String TENANT_ID = "org-alpha";
    static final String USER_ID = "user-alpha";

    /** 基线种子只含 IAM 默认用户；测试用自己的 user-alpha 主体。 */
    private static final String TEST_USER = """
            insert into app_user_account (id, username, display_name, password, enabled)
                values ('user-alpha', 'alpha', 'Alpha User', 'sm3-hash', true);
            """;

    private AgentTestDatabase() {
    }

    static JdbcTemplate create() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:agent-recruit-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__baseline.sql"));
            // 基线之后的增量迁移按序执行（与生产 Flyway 顺序一致）。
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V2__node_session_native_guard.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V3__perception_readonly_tools.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V4__honest_write_output_schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V5__agent_tool_abac_policies.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V6__agent_approval_abac_policies.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V7__user_must_change_password.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V8__agent_approval_permission.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V9__drop_outbox_event.sql"));
            // V10：agent_instance.registry_agent_key。执行循环每轮都会读这一列（专家注册表
            // 接入下发门禁的身份桥），漏掉本条会让整包测试在第一次下发时 bad SQL grammar。
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V10__agent_instance_registry_key.sql"));
            // V11：RUNTIME 工具的 tool_package_version 行。缺了它，任何走审批分支的
            // 运行时工具在 createAwaitingToolInvocation 处撞外键，run 直接 FAILED。
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V11__runtime_tool_package_versions.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V12__node_session_native_guard_details.sql"));
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(TEST_USER.getBytes()));
        } catch (SQLException exception) {
            throw new IllegalStateException("agent recruit test schema failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }
}

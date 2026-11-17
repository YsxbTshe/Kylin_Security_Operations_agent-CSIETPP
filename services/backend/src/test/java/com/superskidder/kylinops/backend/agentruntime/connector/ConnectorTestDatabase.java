package com.superskidder.kylinops.backend.agentruntime.connector;

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
 * Connector 测试的最小数据库引导（沿用 agent/AgentTestDatabase 模式）：
 * H2 PostgreSQL 兼容模式，执行 Phase 7 迭代 7.4 压缩后的新基线
 * db/migration/V1__baseline.sql（含 connector_invocation 与工具目录种子）。
 * SingleConnectionDataSource 保证建表与测试 DML 共用同一会话。
 */
final class ConnectorTestDatabase {

    static final String TENANT_ID = "org-alpha";
    static final String USER_ID = "user-alpha";

    /** 基线种子只含 IAM 默认用户；测试用自己的 user-alpha 主体。 */
    private static final String TEST_USER = """
            insert into app_user_account (id, username, display_name, password, enabled)
                values ('user-alpha', 'alpha', 'Alpha User', 'sm3-hash', true);
            """;

    private ConnectorTestDatabase() {
    }

    static JdbcTemplate create() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:connector-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__baseline.sql"));
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(TEST_USER.getBytes()));
        } catch (SQLException exception) {
            throw new IllegalStateException("connector test schema failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }
}

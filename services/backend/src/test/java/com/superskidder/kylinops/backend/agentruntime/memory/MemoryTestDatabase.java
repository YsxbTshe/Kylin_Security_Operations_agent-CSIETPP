package com.superskidder.kylinops.backend.agentruntime.memory;

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
 * 记忆链测试的最小数据库引导（沿用 agent/AgentTestDatabase 模式）：
 * H2（PostgreSQL 兼容模式）上执行 Phase 7 迭代 7.4 压缩后的新基线
 * db/migration/V1__baseline.sql（含 execution_record/memory_candidate/verified_fact）。
 * SingleConnectionDataSource 保证建表与测试 DML 同一会话（H2 2.4.240 CHECK 约束回归）。
 */
final class MemoryTestDatabase {

    static final String TENANT_ID = "org-alpha";
    static final String USER_ID = "user-alpha";

    /** 基线种子只含 IAM 默认用户；测试用自己的 user-alpha 主体。 */
    private static final String TEST_USER = """
            insert into app_user_account (id, username, display_name, password, enabled)
                values ('user-alpha', 'alpha', 'Alpha User', 'sm3-hash', true);
            """;

    private MemoryTestDatabase() {
    }

    static JdbcTemplate create() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:agent-memory-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__baseline.sql"));
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(TEST_USER.getBytes()));
        } catch (SQLException exception) {
            throw new IllegalStateException("agent memory test schema failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }
}

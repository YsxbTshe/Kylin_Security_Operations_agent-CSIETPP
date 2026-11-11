package com.superskidder.kylinops.backend.agentruntime.vfs;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * vfs 测试的最小数据库引导（沿用 artifact 包的模式）：H2（PostgreSQL 兼容模式）
 * 上执行 Phase 7 迭代 7.4 压缩后的新基线 db/migration/V1__baseline.sql
 * （含 agent_artifact 与 skill_* 表）。使用 SingleConnectionDataSource 让建表与
 * 测试 DML 共用同一会话——基线包含 agent_run 等 CHECK 约束，H2 2.4.240 对跨会话
 * CHECK 约束求值存在回归（h2database#4291）。
 */
final class VfsTestDatabase {

    private VfsTestDatabase() {
    }

    static JdbcTemplate create() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:vfs-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__baseline.sql"));
        } catch (SQLException exception) {
            throw new IllegalStateException("V1__baseline.sql failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }
}

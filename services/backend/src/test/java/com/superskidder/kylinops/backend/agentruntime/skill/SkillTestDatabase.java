package com.superskidder.kylinops.backend.agentruntime.skill;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * skill 测试的最小数据库引导（沿用 agent/AgentTestDatabase 模式）：H2（PostgreSQL
 * 兼容模式）上执行 Phase 7 迭代 7.4 压缩后的新基线 db/migration/V1__baseline.sql
 * （含 skill_package/skill_version/skill_file）。
 * 使用 SingleConnectionDataSource 让建表与测试 DML 共用同一会话——基线包含
 * agent_run 等 CHECK 约束，H2 2.4.240 对跨会话 CHECK 约束求值存在回归
 * （h2database#4291）。
 */
final class SkillTestDatabase {

    private SkillTestDatabase() {
    }

    static JdbcTemplate create() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:skill-" + UUID.randomUUID()
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

    static Map<String, byte[]> validPackage() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", """
                ---
                name: 磁盘告警排查
                description: 只读磁盘排查 runbook
                ---
                # 磁盘告警排查
                运行 scripts/analyze.sh 采集信息。
                """.getBytes(StandardCharsets.UTF_8));
        files.put("kylin-skill.yaml", """
                skill_id: kylin.ops.disk-triage
                trust_level: ORG_REVIEWED
                allowed_tools:
                  - kylin.workspace.read
                  - kylin.workspace.bash
                required_commands:
                  - df
                resources:
                  max_memory_mb: 128
                """.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/analyze.sh", "#!/bin/bash\ndf -h\n".getBytes(StandardCharsets.UTF_8));
        files.put("references/runbook.md", "# runbook\n只读步骤。\n".getBytes(StandardCharsets.UTF_8));
        return files;
    }
}

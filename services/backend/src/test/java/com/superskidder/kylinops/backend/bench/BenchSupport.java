package com.superskidder.kylinops.backend.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 基准评测公共支撑：H2（PostgreSQL 兼容模式）上按生产 Flyway 顺序执行全部迁移，
 * 并定位仓库根（用例集 bench/ 与报告落盘 docs/ 都以仓库根为锚）。
 */
final class BenchSupport {

    private BenchSupport() {
    }

    static JdbcTemplate createDatabase() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:bench-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            for (String migration : new String[] {
                    "db/migration/V1__baseline.sql",
                    "db/migration/V2__node_session_native_guard.sql",
                    "db/migration/V3__perception_readonly_tools.sql",
                    "db/migration/V4__honest_write_output_schema.sql",
                    "db/migration/V5__agent_tool_abac_policies.sql",
                    "db/migration/V6__agent_approval_abac_policies.sql",
                    "db/migration/V7__user_must_change_password.sql",
                    "db/migration/V8__agent_approval_permission.sql",
            }) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("bench schema failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }

    /**
     * 是否允许把 Benchmark 报告写回仓库 docs/。
     *
     * <p>默认关闭：接入 failsafe 后 {@code mvn verify} 会执行这些 *IT，构建绝不能顺手
     * 改写仓库内的评估文档（评估报告须显式重新生成并审阅）。显式重生成：
     * {@code mvn -Dbench.regenerate=true verify} 或 {@code BENCH_REGENERATE=true}。
     * 关闭时断言照跑，只是不落盘——门禁效力不打折。
     */
    static boolean reportRegenerationEnabled() {
        return Boolean.parseBoolean(System.getProperty("bench.regenerate", "false"))
                || Boolean.parseBoolean(System.getenv().getOrDefault("BENCH_REGENERATE", "false"));
    }

    /**
     * 人工补写区的起始标记。生成器只负责这一行**之前**的内容；这一行及其之后的一切
     * 在重生成时原样保留。
     *
     * <p>为什么需要它：报告的数值部分必须由代码生成才可信，但「方法学披露」「评测局限」
     * 「可复现性分级」这类内容只能人写——而生成器原本是整文件覆盖，谁重跑一次基准，
     * 谁就把这些诚实披露全部抹掉，且不会有任何警告。这正是最该保住的部分。
     */
    static final String HUMAN_SECTION_MARKER = "<!-- 人工补写区：生成器不覆盖本行及以下内容 -->";

    /**
     * 落盘报告，保留既有文件中人工补写区的内容。
     *
     * @param output       报告路径
     * @param generated    生成器产出的正文（不含人工补写区）
     */
    static void writeReportPreservingHumanSections(Path output, String generated) {
        String preserved = "";
        try {
            if (Files.exists(output)) {
                String existing = Files.readString(output);
                int at = existing.indexOf(HUMAN_SECTION_MARKER);
                if (at >= 0) {
                    preserved = existing.substring(at);
                }
            }
            String body = generated.endsWith("\n") ? generated : generated + "\n";
            if (preserved.isEmpty()) {
                // 首次生成：留下空的人工区骨架，提示下一个人可以往哪写。
                preserved = HUMAN_SECTION_MARKER + "\n\n"
                        + "<!-- 方法学披露、评测局限、可复现性分级等人工内容写在这里；重跑基准不会覆盖。 -->\n";
            }
            Files.writeString(output, body + "\n" + preserved);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to write benchmark report " + output, exception);
        }
    }

    /** 从工作目录（services/backend）向上找仓库根：同时含 bench/ 与 docs/ 的目录。 */
    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("bench")) && Files.isDirectory(current.resolve("docs"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root from " + Path.of("").toAbsolutePath());
    }
}

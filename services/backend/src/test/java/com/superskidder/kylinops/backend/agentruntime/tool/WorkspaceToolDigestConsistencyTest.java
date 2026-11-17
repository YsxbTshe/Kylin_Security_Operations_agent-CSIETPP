package com.superskidder.kylinops.backend.agentruntime.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillCatalogSigner;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 锁定 workspace 工具 digest 的三方一致性：
 * 1) Workspace*Tool 常量 == SkillCatalogSigner.schemaDigest(工具真实 schema JSON)；
 * 2) 新基线（V1__baseline.sql，原 V63 回填后的最终形态）中的 tool_package_version
 *    行 == 同一批常量；
 * 3) signature_hex 保持 'pending'（无运行期签名管线，见基线注释）。
 *
 * 数据库引导沿用 agent/AgentTestDatabase 的最小链路模式：H2（PostgreSQL 兼容模式）
 * 直执行 Phase 7 迭代 7.4 压缩后的新基线 db/migration/V1__baseline.sql。使用
 * SingleConnectionDataSource 让建表与断言查询共用同一会话——H2 2.4.240 对跨会话
 * CHECK 约束求值存在回归（h2database#4291），基线中 agent_run 等约束会误报。
 */
class WorkspaceToolDigestConsistencyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void javaConstantsMatchSchemaDigestOfLiveSchemas() throws Exception {
        for (NodeTool tool : workspaceTools()) {
            String inputDigest = SkillCatalogSigner.schemaDigest(
                    objectMapper.writeValueAsString(tool.inputSchema()));
            String outputDigest = SkillCatalogSigner.schemaDigest(
                    objectMapper.writeValueAsString(tool.outputSchema()));
            assertThat(inputDigest)
                    .as("input schema digest of %s", tool.toolId())
                    .isEqualTo(tool.inputSchemaDigest());
            assertThat(outputDigest)
                    .as("output schema digest of %s", tool.toolId())
                    .isEqualTo(tool.outputSchemaDigest());
            assertThat(tool.inputSchemaDigest()).isNotEqualTo("pending");
            assertThat(tool.outputSchemaDigest()).isNotEqualTo("pending");
            assertThat(tool.catalogDigest()).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void baselineWorkspaceDigestsMatchJavaConstantsOnH2() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:workspace-digest-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__baseline.sql"));
        } catch (SQLException exception) {
            throw new IllegalStateException("V1__baseline.sql failed on H2", exception);
        }

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        for (NodeTool tool : workspaceTools()) {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    select input_schema_digest, output_schema_digest, signature_hex
                    from tool_package_version where id = ?
                    """, tool.packageVersionId());
            assertThat(row.get("INPUT_SCHEMA_DIGEST"))
                    .as("baseline input digest for %s", tool.toolId())
                    .isEqualTo(tool.inputSchemaDigest());
            assertThat(row.get("OUTPUT_SCHEMA_DIGEST"))
                    .as("baseline output digest for %s", tool.toolId())
                    .isEqualTo(tool.outputSchemaDigest());
            assertThat(row.get("SIGNATURE_HEX"))
                    .as("signature stays pending without a signing pipeline (%s)", tool.toolId())
                    .isEqualTo("pending");
        }
        // 只校验 workspace 工具行：基线中 V57 写工具与 V65 connector 行的 digest
        // 按原口径保持 'pending' 占位（离线签名管线缺失，fail-closed 消费）。
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from tool_package_version
                where id like 'toolpkgver-workspace-%'
                  and (input_schema_digest = 'pending' or output_schema_digest = 'pending')
                """, Long.class)).isZero();
    }

    private List<NodeTool> workspaceTools() {
        return List.of(
                new WorkspaceBashTool(objectMapper),
                new WorkspacePythonTool(objectMapper),
                new WorkspaceReadTool(objectMapper),
                new WorkspaceWriteTool(objectMapper),
                new WorkspaceEditTool(objectMapper),
                new WorkspaceGlobTool(objectMapper));
    }
}

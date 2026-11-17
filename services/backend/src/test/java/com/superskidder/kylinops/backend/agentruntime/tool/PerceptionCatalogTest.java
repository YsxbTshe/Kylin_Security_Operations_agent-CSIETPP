package com.superskidder.kylinops.backend.agentruntime.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class PerceptionCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void catalogMirrorsGoReadOnlyPerceptionToolsAndRejectsUnknownArguments() {
        PerceptionCatalog catalog = new PerceptionCatalog(objectMapper);
        Set<String> ids = catalog.tools().stream().map(NodeTool::toolId).collect(Collectors.toSet());

        assertThat(ids).hasSize(29);
        assertThat(ids).contains(
                "kylin.disk.usage",
                "kylin.diagnosis.triage",
                "kylin.log.journal_query",
                "kylin.cleanup.plan",
                "kylin.security.kysec_status");
        assertThat(ids).doesNotContain("kylin.service.status", "kylin.workspace.read");

        ToolRegistry registry = new ToolRegistry(List.of(new ServiceStatusTool(objectMapper)), catalogProvider(catalog));
        assertThat(registry.lookup("kylin.disk.usage")).isPresent();
        assertThat(registry.lookup("kylin.service.status")).isPresent();
        assertThat(registry.readOnlyTools().stream().map(NodeTool::toolId).toList())
                .contains("kylin.disk.usage", "kylin.service.status");

        NodeTool disk = registry.require("kylin.disk.usage");
        assertThat(disk.validateArguments(Map.of())).isEmpty();
        assertThat(disk.validateOutput("{\"output\":\"Filesystem Size\\n/dev/sda1 40G\"}"))
                .containsEntry("output", "Filesystem Size\n/dev/sda1 40G");
        assertThat(disk.validateOutput("{\"tool\":\"kylin.diagnosis.triage\",\"summary\":\"host=a\"}"))
                .containsEntry("summary", "host=a");

        NodeTool journal = registry.require("kylin.log.journal_query");
        assertThat(journal.validateArguments(Map.of("unit", "nginx", "lines", 20)))
                .containsEntry("unit", "nginx")
                .containsEntry("lines", 20);
        assertThatThrownBy(() -> journal.validateArguments(Map.of("unit", "nginx", "bogus", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
        assertThatThrownBy(() -> journal.validateArguments(Map.of("lines", 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit");
    }

    @Test
    void packageVersionRowsExistAfterV3() throws Exception {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:perception-catalog-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__baseline.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V2__node_session_native_guard.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V3__perception_readonly_tools.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V4__honest_write_output_schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PerceptionCatalog catalog = new PerceptionCatalog(objectMapper);
        for (NodeTool tool : catalog.tools()) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from tool_package_version where id = ?", Integer.class, tool.packageVersionId());
            assertThat(count).as("seed row for %s", tool.toolId()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "select input_schema_json from tool_package_version where id = 'toolpkgver-firewall-block-v1'",
                String.class)).contains("\"duration\"");
        assertThat(jdbc.queryForObject(
                "select input_schema_json from tool_package_version where id = 'toolpkgver-cleanup-execute-v1'",
                String.class)).contains("archive_then_truncate");
    }

    private static org.springframework.beans.factory.ObjectProvider<PerceptionCatalog> catalogProvider(
            PerceptionCatalog catalog) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public PerceptionCatalog getObject() { return catalog; }
            @Override public PerceptionCatalog getIfAvailable() { return catalog; }
            @Override public PerceptionCatalog getIfUnique() { return catalog; }
        };
    }
}

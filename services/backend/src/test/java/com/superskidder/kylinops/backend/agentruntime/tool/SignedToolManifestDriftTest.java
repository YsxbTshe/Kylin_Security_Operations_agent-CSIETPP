package com.superskidder.kylinops.backend.agentruntime.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SignedToolManifestDriftTest {

    private static final String RESOURCE = "tool-catalog/kylin.core.node-1.1.0.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void javaNodeAndWorkspaceToolsMatchSignedManifestIdentity() throws Exception {
        JsonNode root;
        try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
            root = objectMapper.readTree(stream);
        }
        assertThat(root.path("packageId").asText()).isEqualTo("kylin.core.node");
        assertThat(root.path("version").asText()).isEqualTo("1.1.0");

        ToolRegistry registry = new ToolRegistry(explicitNodeTools(), catalogProvider(new PerceptionCatalog(objectMapper)));
        Set<String> registryIds = registry.allTools().stream().map(NodeTool::toolId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> manifestIds = new LinkedHashSet<>();
        for (JsonNode tool : root.path("tools")) {
            String id = tool.path("id").asText();
            manifestIds.add(id);
            NodeTool registered = registry.lookup(id).orElseThrow(() ->
                    new AssertionError("signed manifest tool missing from ToolRegistry: " + id));
            assertThat(registered.riskFloor()).as("%s riskFloor", id).isEqualTo(tool.path("riskFloor").asText());
            assertThat(registered.readOnly()).as("%s readOnly", id).isEqualTo(tool.path("readOnly").asBoolean());
            assertThat(registered.alwaysRequireApproval()).as("%s alwaysRequireApproval", id)
                    .isEqualTo(tool.path("alwaysRequireApproval").asBoolean());
            assertThat(propertyNames(registered.inputSchema()))
                    .as("%s input properties", id)
                    .containsExactlyInAnyOrderElementsOf(propertyNames(tool.path("inputSchema").path("properties")));
        }
        assertThat(registryIds).containsExactlyInAnyOrderElementsOf(manifestIds);
        assertThat(manifestIds).hasSize(46);
    }

    private List<NodeTool> explicitNodeTools() {
        return List.of(
                new ServiceStatusTool(objectMapper),
                new ServiceStartTool(objectMapper),
                new ServiceStopTool(objectMapper),
                new ServiceRestartTool(objectMapper),
                new ServiceReloadTool(objectMapper),
                new ServiceEnableTool(objectMapper),
                new ServiceDisableTool(objectMapper),
                new FirewallBlockTool(objectMapper),
                new FirewallUnblockTool(objectMapper),
                new CleanupExecuteTool(objectMapper),
                new CleanupRollbackTool(objectMapper),
                new WorkspaceBashTool(objectMapper),
                new WorkspacePythonTool(objectMapper),
                new WorkspaceReadTool(objectMapper),
                new WorkspaceWriteTool(objectMapper),
                new WorkspaceEditTool(objectMapper),
                new WorkspaceGlobTool(objectMapper));
    }

    @SuppressWarnings("unchecked")
    private static List<String> propertyNames(Object schema) {
        if (schema instanceof java.util.Map<?, ?> map) {
            Object properties = map.get("properties");
            if (properties instanceof java.util.Map<?, ?> props) {
                return props.keySet().stream().map(String::valueOf).toList();
            }
        }
        return List.of();
    }

    private static List<String> propertyNames(JsonNode properties) {
        List<String> names = new ArrayList<>();
        Iterator<String> fields = properties.fieldNames();
        while (fields.hasNext()) {
            names.add(fields.next());
        }
        return names;
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

package com.superskidder.kylinops.backend.agentruntime.tool;

import com.superskidder.kylinops.backend.crypto.Sm3;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final Map<String, NodeTool> toolsById;
    private final List<NodeTool> allTools;
    private final String catalogDigest;

    public ToolRegistry(List<NodeTool> tools) {
        this(tools, (PerceptionCatalog) null);
    }

    @Autowired
    public ToolRegistry(List<NodeTool> tools, ObjectProvider<PerceptionCatalog> perceptionCatalog) {
        this(tools, perceptionCatalog == null ? null : perceptionCatalog.getIfAvailable());
    }

    private ToolRegistry(List<NodeTool> tools, PerceptionCatalog perceptionCatalog) {
        List<NodeTool> merged = PerceptionCatalog.merge(tools, perceptionCatalog);
        this.allTools = List.copyOf(merged);
        this.toolsById = merged.stream()
                .collect(Collectors.toMap(NodeTool::toolId, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("duplicate tool id: " + a.toolId());
                }, LinkedHashMap::new));
        String sortedDigestMaterial = allTools.stream()
                .sorted(Comparator.comparing(NodeTool::toolId))
                .map(t -> t.toolId() + ":" + t.version() + ":" + t.riskFloor() + ":" + t.readOnly())
                .collect(Collectors.joining("\n"));
        this.catalogDigest = Sm3.hex(sortedDigestMaterial.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<NodeTool> lookup(String toolId) {
        return Optional.ofNullable(toolsById.get(toolId));
    }

    public NodeTool require(String toolId) {
        return lookup(toolId).orElseThrow(() ->
                new IllegalArgumentException("unknown tool: " + toolId));
    }

    public List<NodeTool> allTools() {
        return allTools;
    }

    public List<NodeTool> writableTools() {
        return allTools.stream().filter(t -> !t.readOnly()).toList();
    }

    public List<NodeTool> readOnlyTools() {
        return allTools.stream().filter(NodeTool::readOnly).toList();
    }

    public List<NodeTool> toolsRequiringApproval() {
        return allTools.stream().filter(NodeTool::alwaysRequireApproval).toList();
    }

    public String catalogDigest() {
        return catalogDigest;
    }

    public List<Map<String, Object>> catalogEntriesForGrant(Map<String, Object> grant) {
        boolean grantReadOnly = Boolean.TRUE.equals(grant.get("readOnly"));
        @SuppressWarnings("unchecked")
        List<String> allowedToolIds = grant.get("tools") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : null;
        return allTools.stream()
                .filter(t -> grantReadOnly ? t.readOnly() : true)
                .filter(t -> allowedToolIds == null || allowedToolIds.contains(t.toolId()))
                .map(t -> Map.<String, Object>of(
                        "name", t.toolId(),
                        "version", t.version(),
                        "readOnly", t.readOnly(),
                        "riskFloor", t.riskFloor(),
                        "alwaysRequireApproval", t.alwaysRequireApproval(),
                        "directDisplay", t.readOnly(),
                        "inputSchema", t.inputSchema(),
                        "outputSchema", t.outputSchema()))
                .toList();
    }
}

package com.superskidder.kylinops.backend.agentruntime.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 镜像 Go {@code mcp.defaultCatalog} 中尚未以独立 NodeTool 类存在的只读感知/诊断工具。
 * 已有的 {@code kylin.service.status} / workspace.read / workspace.glob 不在此重复注册。
 */
@Component
public class PerceptionCatalog {

    private final List<NodeTool> tools;

    public PerceptionCatalog(ObjectMapper objectMapper) {
        this.tools = List.copyOf(buildAll(objectMapper));
    }

    public List<NodeTool> tools() {
        return tools;
    }

    static List<NodeTool> buildAll(ObjectMapper objectMapper) {
        List<NodeTool> tools = new ArrayList<>();
        tools.add(tool(objectMapper, "kylin.disk.usage", "Read disk usage", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.disk.large_files",
                "Find large regular files under an allowed path root",
                objectSchema(Map.of("targetDir", stringSchema(), "maxFiles", integerSchema(1, 100)), List.of()),
                lock("path:{targetDir}", "READ_SHARED")));
        tools.add(tool(objectMapper, "kylin.io.snapshot",
                "Read disk I/O counters from /proc/diskstats",
                objectSchema(Map.of("limit", integerSchema(1, 100)), List.of())));
        tools.add(tool(objectMapper, "kylin.metrics.node_exporter_snapshot",
                "Collect the configured node_exporter metrics snapshot",
                objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.process.list",
                "List processes",
                objectSchema(Map.of("limit", integerSchema(1, 200)), List.of())));
        tools.add(tool(objectMapper, "kylin.process.top_cpu",
                "List top CPU consuming processes",
                objectSchema(Map.of("limit", integerSchema(1, 100)), List.of())));
        tools.add(tool(objectMapper, "kylin.process.top_memory",
                "List top memory consuming processes",
                objectSchema(Map.of("limit", integerSchema(1, 100)), List.of())));
        tools.add(tool(objectMapper, "kylin.firewall.state",
                "Read firewalld runtime state", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.firewall.rules",
                "List firewalld public zone rules", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.net.listening_ports",
                "List listening TCP/UDP ports", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.net.connections",
                "List active TCP/UDP connections", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.log.journal_query",
                "Query journald logs for an allowlisted service",
                objectSchema(Map.of(
                        "unit", stringSchema(),
                        "lines", integerSchema(1, 500),
                        "priority", enumSchema(List.of("debug", "info", "notice", "warning", "err", "crit", "alert", "emerg"))),
                        List.of("unit"))));
        tools.add(tool(objectMapper, "kylin.file.lsof_path",
                "List open files for an allowed path",
                objectSchema(Map.of("path", stringSchema()), List.of("path")),
                lock("path:{path}", "READ_SHARED")));
        tools.add(tool(objectMapper, "kylin.file.stat",
                "Read metadata for an allowed file path",
                objectSchema(Map.of("path", stringSchema()), List.of("path")),
                lock("path:{path}", "READ_SHARED")));
        tools.add(tool(objectMapper, "kylin.file.sm3",
                "Read SM3 digest for an allowed file path",
                objectSchema(Map.of("path", stringSchema()), List.of("path")),
                lock("path:{path}", "READ_SHARED")));
        tools.add(tool(objectMapper, "kylin.system.sysctl_snapshot",
                "Read kernel sysctl parameters", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.system.mounts",
                "List mounted filesystems", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.package.list",
                "List installed RPM packages", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.package.info",
                "Read RPM package metadata",
                objectSchema(Map.of("package", stringSchema()), List.of("package"))));
        tools.add(tool(objectMapper, "kylin.user.accounts",
                "List local user accounts", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.user.privileged_groups",
                "List common privileged group members", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.user.last_logins",
                "List recent login records",
                objectSchema(Map.of("limit", integerSchema(1, 200)), List.of())));
        tools.add(tool(objectMapper, "kylin.config.permissions_snapshot",
                "Read key configuration file permissions", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.diagnosis.triage",
                "Collect a read-only host triage summary", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.cleanup.plan",
                "Plan archive and truncate cleanup for approved demo directories",
                objectSchema(Map.of("targetDir", stringSchema(), "maxFiles", integerSchema(1, 100)), List.of()),
                lock("path:{targetDir}", "READ_SHARED")));
        tools.add(tool(objectMapper, "kylin.security.kysec_status",
                "Read KYSEC security status when available", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.security.selinux_status",
                "Read SELinux enforce/permissive/disabled status", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.security.auditd_status",
                "Read auditd service status", objectSchema(Map.of(), List.of())));
        tools.add(tool(objectMapper, "kylin.security.security_switch_status",
                "Read Kylin security-switch status when available", objectSchema(Map.of(), List.of())));
        return tools;
    }

    static String packageVersionId(String toolId) {
        return "toolpkgver-" + toolId.substring("kylin.".length()).replace('.', '-') + "-v1";
    }

    private static PerceptionNodeTool tool(ObjectMapper objectMapper, String toolId, String description,
                                           Map<String, Object> inputSchema, NodeTool.ResourceLockTemplate... locks) {
        return new PerceptionNodeTool(objectMapper, toolId, description, packageVersionId(toolId),
                inputSchema, List.of(locks));
    }

    private static NodeTool.ResourceLockTemplate lock(String pattern, String mode) {
        return new NodeTool.ResourceLockTemplate(pattern, mode);
    }

    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    static Map<String, Object> stringSchema() {
        return Map.of("type", "string", "minLength", 1);
    }

    static Map<String, Object> integerSchema(int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    static Map<String, Object> enumSchema(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }

    static List<NodeTool> merge(List<NodeTool> tools, PerceptionCatalog extra) {
        List<NodeTool> merged = new ArrayList<>(tools == null ? List.of() : tools);
        if (extra != null) {
            for (NodeTool tool : extra.tools()) {
                boolean exists = merged.stream().anyMatch(existing -> existing.toolId().equals(tool.toolId()));
                if (!exists) {
                    merged.add(tool);
                }
            }
        }
        return merged;
    }
}

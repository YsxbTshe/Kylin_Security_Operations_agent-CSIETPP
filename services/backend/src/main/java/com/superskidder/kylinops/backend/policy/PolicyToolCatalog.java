package com.superskidder.kylinops.backend.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 策略引擎的工具目录：内置 Kylin 运维工具分级 + 受管主机上报清单的夹紧合并。
 *
 * <p>Phase 7 迭代 7.2 从旧执行链路 {@code hostcontrol.HostAgentToolCatalogService} 迁入
 * policy 包并实现 {@link ToolDefinitionProvider}，使 {@link PolicyEngine} 不再依赖旧链路；
 * 目录内容与夹紧规则原样保留，策略语义不变。
 */
@Service
public class PolicyToolCatalog implements ToolDefinitionProvider {

    private final Map<String, ToolDefinition> definitions = buildDefinitions();
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PolicyToolCatalog(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ToolDefinition> listTools() {
        return mergedDefinitions().values().stream()
                .sorted(Comparator.comparing(ToolDefinition::category)
                        .thenComparing(ToolDefinition::toolName))
                .toList();
    }

    @Override
    public List<ToolDefinition> describeTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        Map<String, ToolDefinition> merged = mergedDefinitions();
        return toolNames.stream()
                .map(toolName -> describeTool(merged, toolName))
                .toList();
    }

    private ToolDefinition describeTool(Map<String, ToolDefinition> merged, String toolName) {
        ToolDefinition known = merged.get(toolName);
        if (known != null) {
            return known;
        }
        return new ToolDefinition(
                toolName,
                "plugin",
                false,
                true,
                "Host Agent reported plugin or extension tool",
                "R3"
        );
    }

    private Map<String, ToolDefinition> buildDefinitions() {
        Map<String, ToolDefinition> items = new LinkedHashMap<>();
        addReadOnly(items, "kylin.disk.usage", "resource", "Read disk usage");
        addReadOnly(items, "kylin.disk.large_files", "resource", "Find large files under an allowlisted path");
        addReadOnly(items, "kylin.io.snapshot", "resource", "Read disk I/O counters");
        addReadOnly(items, "kylin.metrics.node_exporter_snapshot", "metrics", "Read node_exporter metrics snapshot");
        addReadOnly(items, "kylin.process.list", "process", "List processes");
        addReadOnly(items, "kylin.process.top_cpu", "process", "List top CPU consuming processes");
        addReadOnly(items, "kylin.process.top_memory", "process", "List top memory consuming processes");
        addReadOnly(items, "kylin.net.listening_ports", "network", "List listening TCP/UDP ports");
        addReadOnly(items, "kylin.net.connections", "network", "List active network connections");
        addReadOnly(items, "kylin.file.lsof_path", "file", "Run lsof for an allowlisted path");
        addReadOnly(items, "kylin.file.stat", "file", "Read metadata for an allowlisted file path");
        addReadOnly(items, "kylin.file.sm3", "file", "Read SM3 digest for an allowlisted file path");
        addReadOnly(items, "kylin.system.sysctl_snapshot", "system", "Read kernel sysctl parameters");
        addReadOnly(items, "kylin.system.mounts", "system", "List mounted filesystems");
        addReadOnly(items, "kylin.package.list", "package", "List installed RPM packages");
        addReadOnly(items, "kylin.package.info", "package", "Read RPM package metadata");
        addReadOnly(items, "kylin.user.accounts", "user", "List local user accounts");
        addReadOnly(items, "kylin.user.privileged_groups", "user", "List privileged group members");
        addReadOnly(items, "kylin.user.last_logins", "user", "List recent login records");
        addReadOnly(items, "kylin.config.permissions_snapshot", "config", "Read key configuration file permissions");
        addReadOnly(items, "kylin.diagnosis.triage", "diagnosis", "Collect host summary for first-pass diagnosis");
        addReadOnly(items, "kylin.result.verify", "verification", "Verify operation result with read-only evidence");
        addReadOnly(items, "kylin.service.status", "systemd", "Read status for an allowlisted service");
        addAction(items, "kylin.service.start", "systemd", "Start an allowlisted service",
                ToolCompensation.reversible("kylin.service.stop", "确认该服务在本次任务前处于停止状态"));
        addAction(items, "kylin.service.stop", "systemd", "Stop an allowlisted service",
                ToolCompensation.reversible("kylin.service.start", "确认该服务在本次任务前处于运行状态"));
        // 重启已丢失原进程状态（PID、内存中的会话、未持久化的运行时配置），无法通过再次调用还原
        addAction(items, "kylin.service.restart", "systemd", "Restart an allowlisted service",
                ToolCompensation.noInverse("重启已丢失原进程状态，无逆操作；但服务本身会恢复可用"));
        // 配置已重载，旧配置未必仍在磁盘上
        addAction(items, "kylin.service.reload", "systemd", "Reload an allowlisted service",
                ToolCompensation.noInverse("配置已重载，旧配置未必留存；服务本身保持可用"));
        addAction(items, "kylin.service.enable", "systemd", "Enable an allowlisted service at boot",
                ToolCompensation.reversible("kylin.service.disable", "确认该服务在本次任务前未设置开机自启"));
        addAction(items, "kylin.service.disable", "systemd", "Disable an allowlisted service at boot",
                ToolCompensation.reversible("kylin.service.enable", "确认该服务在本次任务前已设置开机自启"));
        addReadOnly(items, "kylin.firewall.state", "firewall", "Read firewalld runtime state");
        addReadOnly(items, "kylin.firewall.rules", "firewall", "List firewalld public zone rules");
        addAction(items, "kylin.firewall.block_ip", "firewall", "Block an IP via firewalld",
                ToolCompensation.reversible("kylin.firewall.unblock_ip", "确认该封禁规则确由本次任务添加，避免解除既有规则"));
        addAction(items, "kylin.firewall.unblock_ip", "firewall", "Unblock an IP via firewalld",
                ToolCompensation.reversible("kylin.firewall.block_ip", "需要原规则的 scope 与 duration 参数才能完整还原"));
        addReadOnly(items, "kylin.log.journal_query", "logs", "Query journald logs for an allowlisted service");
        addReadOnly(items, "kylin.cleanup.plan", "cleanup", "Plan archive and truncate cleanup with rollback support");
        addAction(items, "kylin.cleanup.execute", "cleanup", "Archive and truncate approved demo logs with rollback metadata",
                ToolCompensation.reversibleFromResult("kylin.cleanup.rollback", "rollbackId", "回滚归档必须仍然存在"));
        addAction(items, "kylin.cleanup.rollback", "cleanup", "Restore files by rollback_id",
                ToolCompensation.compensating("本身即补偿动作"));
        addReadOnly(items, "kylin.security.kysec_status", "security", "Read KYSEC security status when available");
        addReadOnly(items, "kylin.security.selinux_status", "security", "Read SELinux enforce/permissive/disabled status");
        addReadOnly(items, "kylin.security.auditd_status", "security", "Read auditd service status");
        addReadOnly(items, "kylin.security.security_switch_status", "security", "Read Kylin security-switch status when available");
        // 控制面自有的 RUNTIME 只读工具（不下发节点，在 Runtime 进程内执行，无主机副作用）。
        //
        // 必须在这里显式声明：describeTool 对目录外的工具 fail-closed 到 R3 + requiresApproval，
        // 那条兜底是给「端侧上报的未知插件工具」用的。控制面自己的工具若也落进兜底，
        // DispatchGate 会把一次纯读检索判成 R3 —— 既要 Native Guard 姿态新鲜，又要走审批，
        // 而 RUNTIME 工具在审批分支上会去写 tool_invocation（外键指向 tool_package_version，
        // 运行时工具没有这行）——实际表现是整条 run 直接失败。
        //
        // 声明为 R0/只读的依据是它们的执行面：只读租户已发布的 Skill 元数据与正文、
        // 只读 VFS（skill/workspace/artifact 三个 resolver 自身的租户与归属判定不受影响），
        // 不触达受管主机，也不产生任何可变状态。
        addReadOnly(items, "kylin.skill.search", "skill",
                "Search published Skill metadata of the current tenant (progressive disclosure layer 1)");
        addReadOnly(items, "kylin.skill.load", "skill",
                "Load the body or resource file of a published Skill (progressive disclosure layer 2/3)");
        addReadOnly(items, "kylin.vfs.read", "vfs",
                "Read a resource from the agent virtual file system by vfs URI");
        return Map.copyOf(items);
    }

    private Map<String, ToolDefinition> mergedDefinitions() {
        Map<String, ToolDefinition> merged = new LinkedHashMap<>(definitions);
        try {
            List<String> manifests = jdbcTemplate.query("""
                    select tool_manifest_json
                    from app_managed_host
                    where tool_manifest_json is not null and trim(tool_manifest_json) <> ''
                    order by last_heartbeat_at desc nulls last
                    """, (rs, rowNum) -> rs.getString("tool_manifest_json"));
            for (String manifest : manifests) {
                mergeManifest(merged, manifest);
            }
        } catch (Exception ignored) {
            return merged;
        }
        return merged;
    }


    /**
     * 把 Host 上报的工具声明夹紧到控制面的判定之内：**风险只能提升，不能降低**。
     *
     * <p>威胁模型（ADR-001 §2.10.1）假定端侧可能已被攻陷。若直接采信上报值，一台被控主机
     * 只需在 manifest 里把 {@code kylin.firewall.block_ip} 声明成 {@code R0 / readOnly}，
     * 后端就会认为它无需审批——一次防火墙写操作即可零人工授权执行。该路径已实测可复现。
     *
     * <p>因此：
     * <ul>
     *   <li>控制面已知的工具：{@code riskCode} 取两者较高者，{@code readOnly} 只能由真变假，
     *       {@code requiresApproval} 只能由假变真；</li>
     *   <li>控制面未知的插件工具：不信任其自述，一律按需审批的写操作处理（至少 R3）。</li>
     * </ul>
     *
     * <p>描述、资源锁与路径白名单等非安全判定字段仍采用上报值。
     */
    private ToolDefinition clampToControlPlane(
            ToolDefinition authoritative, ToolDefinition reported) {
        RiskCode reportedRisk = RiskCode.normalize(reported.riskCode());
        RiskCode floor = authoritative == null
                // 未知插件工具：不因为它自称只读就放行
                ? RiskCode.R3
                : RiskCode.normalize(authoritative.riskCode());
        RiskCode effective = reportedRisk.ordinal() >= floor.ordinal() ? reportedRisk : floor;

        boolean readOnly = reported.readOnly()
                && (authoritative == null ? false : authoritative.readOnly());
        boolean requiresApproval = reported.requiresApproval()
                || (authoritative != null && authoritative.requiresApproval())
                || effective.ordinal() >= RiskCode.R3.ordinal();

        return new ToolDefinition(
                reported.toolName(),
                reported.category(),
                readOnly,
                requiresApproval,
                reported.description(),
                effective.name(),
                reported.resourceLocks(),
                reported.allowedRoots(),
                reported.blockedPaths(),
                // 补偿声明只认控制面：端侧若能自称"可逆"，即可绕过不可逆动作的高危拦截
                authoritative == null
                        ? ToolCompensation.noInverse("未知插件工具，按无补偿动作处理")
                        : authoritative.compensation()
        );
    }

    private void mergeManifest(Map<String, ToolDefinition> merged, String manifestJson) {
        try {
            JsonNode tools = objectMapper.readTree(manifestJson).path("tools");
            if (!tools.isArray()) {
                return;
            }
            for (JsonNode tool : tools) {
                String name = textValue(tool, "name", "toolName", "tool_name");
                if (name.isBlank()) {
                    continue;
                }
                String riskCode = textValue(tool, "riskCode", "risk_code");
                boolean readOnly = booleanValue(tool, "R0".equalsIgnoreCase(riskCode), "readOnly", "read_only");
                boolean requiresApproval = booleanValue(tool, !readOnly && ("R3".equalsIgnoreCase(riskCode) || "R4".equalsIgnoreCase(riskCode)), "requiresApproval", "requires_approval");
                merged.put(name, clampToControlPlane(
                        merged.get(name),
                        new ToolDefinition(
                                name,
                                category(name),
                                readOnly,
                                requiresApproval,
                                tool.path("description").asText("Host Agent manifest tool"),
                                normalizeRiskCode(riskCode, readOnly, requiresApproval),
                                resourceLocks(tool),
                                textArray(tool, "allowedRoots", "allowed_roots"),
                                textArray(tool, "blockedPaths", "blocked_paths")
                        )));
            }
        } catch (Exception ignored) {
            // Ignore one bad host manifest without hiding the static catalog.
        }
    }

    private List<ToolDefinition.ResourceLock> resourceLocks(JsonNode tool) {
        JsonNode rawLocks = tool.path("resourceLocks");
        if (!rawLocks.isArray()) {
            rawLocks = tool.path("resource_locks");
        }
        if (!rawLocks.isArray()) {
            return List.of();
        }
        List<ToolDefinition.ResourceLock> locks = new ArrayList<>();
        for (JsonNode lock : rawLocks) {
            String resource = textValue(lock, "resourceKey", "resource", "template");
            String type = textValue(lock, "lockType", "type");
            if (!resource.isBlank() && !type.isBlank()) {
                locks.add(new ToolDefinition.ResourceLock(resource, type));
            }
        }
        return List.copyOf(locks);
    }

    private List<String> textArray(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isArray()) {
                continue;
            }
            List<String> result = new ArrayList<>();
            for (JsonNode item : value) {
                String text = item.asText("").trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    private String textValue(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("");
            if (!value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean booleanValue(JsonNode node, boolean fallback, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asBoolean(fallback);
            }
        }
        return fallback;
    }

    private String normalizeRiskCode(String value, boolean readOnly, boolean requiresApproval) {
        String riskCode = value == null ? "" : value.trim().toUpperCase();
        if (riskCode.matches("R[0-5]")) {
            return riskCode;
        }
        if (requiresApproval) {
            return "R3";
        }
        return readOnly ? "R0" : "R2";
    }

    private String category(String toolName) {
        String[] parts = toolName == null ? new String[0] : toolName.split("\\.");
        if (parts.length >= 2) {
            return parts[1];
        }
        return "manifest";
    }

    private void addReadOnly(Map<String, ToolDefinition> items, String toolName, String category, String description) {
        items.put(toolName, new ToolDefinition(toolName, category, true, false, description, "R0"));
    }

    private void addAction(Map<String, ToolDefinition> items, String toolName, String category,
                           String description, ToolCompensation compensation) {
        items.put(toolName, new ToolDefinition(toolName, category, false, true, description, "R3",
                List.of(), List.of(), List.of(), compensation));
    }
}

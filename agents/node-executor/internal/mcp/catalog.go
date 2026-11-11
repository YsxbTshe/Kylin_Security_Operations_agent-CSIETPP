package mcp

import (
	"encoding/json"
	"slices"
	"strings"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/cleanupcore"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/gmcrypto"
)

// cleanupAllowedRoots 是三个清理工具对外声明的路径根。
//
// 必须跟着 root 持有的 cleanup.conf 走，而不是写死两个演示目录：控制面的
// PolicyEngine 会按节点上报的 allowedRoots 判 R5（POLICY_MANIFEST_ALLOWED_ROOTS），
// 若这里仍写死，部署方在 cleanup.conf 里加的目录会在控制面就被拦下，
// 只留下一句「参数路径超出 allowedRoots」，配置看起来毫无作用。
//
// 这不是把边界交给节点：真正的强制点仍是 privhelper 侧的 ResolveTarget 精确匹配，
// 而 AllowedDirs 本身来自 root 属主的配置文件（读不到就 fail-closed 回到两个演示目录）。
// 这里只是让上报的声明与实际生效的白名单保持一致，避免两处漂移。
//
// 调用时机要求：bootstrap.New 先 cleanupcore.Configure 再 NewCatalog。
func cleanupAllowedRoots() []string {
	return append([]string(nil), cleanupcore.AllowedDirs...)
}

type Catalog struct {
	tools map[string]ToolDefinition
}

type ToolDefinition struct {
	Name             string         `json:"name"`
	InternalName     string         `json:"-"`
	Description      string         `json:"description"`
	InputSchema      map[string]any `json:"inputSchema"`
	OutputSchema     map[string]any `json:"outputSchema,omitempty"`
	RiskCode         string         `json:"-"`
	RequiresApproval bool           `json:"-"`
	ReadOnly         bool           `json:"-"`
	ResourceLocks    []ResourceLock `json:"-"`
	AllowedRoots     []string       `json:"-"`
	BlockedPaths     []string       `json:"-"`
}

type ResourceLock struct {
	ResourceKey string `json:"resourceKey,omitempty"`
	LockType    string `json:"lockType,omitempty"`
	Template    string `json:"template,omitempty"`
	Type        string `json:"type,omitempty"`
}

type manifestFile struct {
	PluginName string         `json:"pluginName"`
	Version    string         `json:"version"`
	Tools      []manifestTool `json:"tools"`
}

type manifestTool struct {
	ToolName         string         `json:"toolName"`
	Name             string         `json:"name"`
	Handler          string         `json:"handler"`
	Description      string         `json:"description"`
	InputSchema      map[string]any `json:"inputSchema"`
	OutputSchema     map[string]any `json:"outputSchema"`
	RiskCode         string         `json:"riskCode"`
	RequiresApproval bool           `json:"requiresApproval"`
	ReadOnly         bool           `json:"readOnly"`
	ResourceLocks    []ResourceLock `json:"resourceLocks"`
	AllowedRoots     []string       `json:"allowedRoots"`
	BlockedPaths     []string       `json:"blockedPaths"`
}

// NewCatalog 返回编译进二进制的默认工具目录。
//
// 迭代 7.3 起不再在运行时扫描插件目录合并 JSON：权威运行时目录是
// defaultCatalog（编进二进制）。tool-packages/core-node-tools 的 SM2 签名清单
// （当前 1.1.0）与 generated/tool-catalog 投影用于离线评审与漂移测试，运行时不加载。
func NewCatalog() Catalog {
	return defaultCatalog()
}

func defaultOutputSchema() map[string]any {
	return objectSchema(map[string]any{
		"toolName": stringSchema(),
		"output":   map[string]any{"type": "string"},
	}, []string{"toolName", "output"})
}

func (c Catalog) Tools() []ToolDefinition {
	items := make([]ToolDefinition, 0, len(c.tools))
	for _, item := range c.tools {
		items = append(items, item)
	}
	slices.SortFunc(items, func(left, right ToolDefinition) int { return strings.Compare(left.Name, right.Name) })
	return items
}

func (c Catalog) PublicTools() []Tool {
	items := c.Tools()
	result := make([]Tool, 0, len(items))
	for _, item := range items {
		result = append(result, Tool{Name: item.Name, Description: item.Description, InputSchema: item.InputSchema, OutputSchema: item.OutputSchema})
	}
	return result
}

func (c Catalog) SupportedToolNames() []string {
	items := c.Tools()
	names := make([]string, 0, len(items))
	for _, item := range items {
		names = append(names, item.Name)
	}
	return names
}

func (c Catalog) ManifestJSON() string {
	payload, err := json.MarshalIndent(c.Manifest(), "", "  ")
	if err != nil {
		return "{}"
	}
	return string(payload)
}

func (c Catalog) VersionHash() string {
	return gmcrypto.SM3HexString(c.ManifestJSON())
}

func (c Catalog) Manifest() manifestFile {
	tools := make([]manifestTool, 0, len(c.tools))
	for _, item := range c.Tools() {
		tools = append(tools, manifestTool{
			ToolName:         item.Name,
			Name:             item.Name,
			Handler:          item.InternalName,
			Description:      item.Description,
			InputSchema:      item.InputSchema,
			OutputSchema:     item.OutputSchema,
			RiskCode:         item.RiskCode,
			RequiresApproval: item.RequiresApproval,
			ReadOnly:         item.ReadOnly,
			ResourceLocks:    append([]ResourceLock(nil), item.ResourceLocks...),
			AllowedRoots:     append([]string(nil), item.AllowedRoots...),
			BlockedPaths:     append([]string(nil), item.BlockedPaths...),
		})
	}
	return manifestFile{
		PluginName: "kylin-node-executor",
		Version:    "0.1.0-safeops",
		Tools:      tools,
	}
}

func (c Catalog) Lookup(name string) (ToolDefinition, bool) {
	definition, ok := c.tools[strings.TrimSpace(name)]
	return definition, ok
}

func (c Catalog) IsSupportedTool(name string) bool {
	_, ok := c.Lookup(name)
	return ok
}

func (c Catalog) MinimumToolRisk(name string) string {
	definition, ok := c.Lookup(name)
	if !ok {
		return ""
	}
	return normalizeRisk(definition.RiskCode)
}

func (c Catalog) InternalToolName(name string) (string, bool) {
	definition, ok := c.Lookup(name)
	if !ok {
		return "", false
	}
	return definition.InternalName, true
}

func defaultCatalog() Catalog {
	catalog := Catalog{tools: map[string]ToolDefinition{}}
	for _, item := range []ToolDefinition{
		{Name: "kylin.disk.usage", InternalName: "disk_usage", Description: "Read disk usage", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.disk.large_files", InternalName: "disk_large_files", Description: "Find large regular files under an allowed path root", InputSchema: objectSchema(map[string]any{"targetDir": stringSchema(), "maxFiles": integerSchema(1, 100)}, nil), RiskCode: "R0", ReadOnly: true, ResourceLocks: []ResourceLock{{ResourceKey: "path:${targetDir}", LockType: "READ_SHARED"}}, AllowedRoots: []string{"/var/log", "/opt", "/tmp/aiops-demo-logs", "/var/log/aiops-demo"}, BlockedPaths: []string{"/", "/etc", "/boot", "/usr", "/var/lib/mysql", "/var/lib/postgresql", "/var/log/audit"}},
		{Name: "kylin.io.snapshot", InternalName: "io_snapshot", Description: "Read disk I/O counters from /proc/diskstats", InputSchema: objectSchema(map[string]any{"limit": integerSchema(1, 100)}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.metrics.node_exporter_snapshot", InternalName: "node_exporter_snapshot", Description: "Collect the configured node_exporter metrics snapshot", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.process.list", InternalName: "process_list", Description: "List processes", InputSchema: objectSchema(map[string]any{"limit": integerSchema(1, 200)}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.process.top_cpu", InternalName: "top_cpu_processes", Description: "List top CPU consuming processes", InputSchema: objectSchema(map[string]any{"limit": integerSchema(1, 100)}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.process.top_memory", InternalName: "top_memory_processes", Description: "List top memory consuming processes", InputSchema: objectSchema(map[string]any{"limit": integerSchema(1, 100)}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.service.status", InternalName: "service_status", Description: "Read status for an allowlisted service", InputSchema: objectSchema(map[string]any{"service": stringSchema()}, []string{"service"}), OutputSchema: objectSchema(map[string]any{"service": stringSchema(), "activeState": stringSchema(), "subState": stringSchema(), "loadState": stringSchema(), "unitFileState": stringSchema()}, []string{"service", "activeState", "subState", "loadState", "unitFileState"}), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.service.start", InternalName: "start_service", Description: "Start an allowlisted service", InputSchema: objectSchema(map[string]any{"service": stringSchema()}, []string{"service"}), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "service:${service}", LockType: "WRITE_EXCLUSIVE"}}},
		{Name: "kylin.service.stop", InternalName: "stop_service", Description: "Stop an allowlisted service", InputSchema: objectSchema(map[string]any{"service": stringSchema()}, []string{"service"}), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "service:${service}", LockType: "WRITE_EXCLUSIVE"}}},
		{Name: "kylin.service.restart", InternalName: "restart_service", Description: "Restart an allowlisted service", InputSchema: objectSchema(map[string]any{"service": stringSchema()}, []string{"service"}), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "service:${service}", LockType: "WRITE_EXCLUSIVE"}}},
		{Name: "kylin.service.reload", InternalName: "reload_service", Description: "Reload an allowlisted service", InputSchema: objectSchema(map[string]any{"service": stringSchema()}, []string{"service"}), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "service:${service}", LockType: "WRITE_EXCLUSIVE"}}},
		{Name: "kylin.service.enable", InternalName: "enable_service", Description: "Enable an allowlisted service at boot", InputSchema: objectSchema(map[string]any{"service": stringSchema()}, []string{"service"}), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "service:${service}", LockType: "WRITE_EXCLUSIVE"}}},
		{Name: "kylin.service.disable", InternalName: "disable_service", Description: "Disable an allowlisted service at boot", InputSchema: objectSchema(map[string]any{"service": stringSchema()}, []string{"service"}), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "service:${service}", LockType: "WRITE_EXCLUSIVE"}}},
		{Name: "kylin.firewall.state", InternalName: "firewall_state", Description: "Read firewalld runtime state", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.firewall.rules", InternalName: "firewall_rules", Description: "List firewalld public zone rules", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.firewall.block_ip", InternalName: "block_ip", Description: "Block an IP via firewalld", InputSchema: objectSchema(map[string]any{"ip": stringSchema(), "scope": stringSchema(), "duration": stringSchema()}, []string{"ip", "scope", "duration"}), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "firewall:${ip}", LockType: "WRITE_EXCLUSIVE"}}},
		{Name: "kylin.firewall.unblock_ip", InternalName: "unblock_ip", Description: "Unblock an IP via firewalld", InputSchema: objectSchema(map[string]any{"ip": stringSchema(), "scope": stringSchema()}, []string{"ip", "scope"}), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "firewall:${ip}", LockType: "WRITE_EXCLUSIVE"}}},
		{Name: "kylin.net.listening_ports", InternalName: "list_listening_ports", Description: "List listening TCP/UDP ports", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.net.connections", InternalName: "list_connections", Description: "List active TCP/UDP connections", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.log.journal_query", InternalName: "journalctl_query", Description: "Query journald logs for an allowlisted service", InputSchema: objectSchema(map[string]any{"unit": stringSchema(), "lines": integerSchema(1, 500), "priority": enumSchema([]string{"debug", "info", "notice", "warning", "err", "crit", "alert", "emerg"})}, []string{"unit"}), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.file.lsof_path", InternalName: "lsof_path", Description: "List open files for an allowed path", InputSchema: objectSchema(map[string]any{"path": stringSchema()}, []string{"path"}), RiskCode: "R0", ReadOnly: true, ResourceLocks: []ResourceLock{{ResourceKey: "path:${path}", LockType: "READ_SHARED"}}, AllowedRoots: []string{"/var/log", "/etc", "/opt", "/tmp/aiops-demo-logs", "/var/log/aiops-demo"}, BlockedPaths: []string{"/", "/boot", "/var/lib/mysql", "/var/lib/postgresql", "/var/log/audit"}},
		{Name: "kylin.file.stat", InternalName: "file_stat", Description: "Read metadata for an allowed file path", InputSchema: objectSchema(map[string]any{"path": stringSchema()}, []string{"path"}), RiskCode: "R0", ReadOnly: true, ResourceLocks: []ResourceLock{{ResourceKey: "path:${path}", LockType: "READ_SHARED"}}, AllowedRoots: []string{"/var/log", "/etc", "/opt", "/tmp/aiops-demo-logs", "/var/log/aiops-demo"}, BlockedPaths: []string{"/", "/boot", "/var/lib/mysql", "/var/lib/postgresql", "/var/log/audit"}},
		{Name: "kylin.file.sm3", InternalName: "file_sm3", Description: "Read SM3 digest for an allowed file path", InputSchema: objectSchema(map[string]any{"path": stringSchema()}, []string{"path"}), RiskCode: "R0", ReadOnly: true, ResourceLocks: []ResourceLock{{ResourceKey: "path:${path}", LockType: "READ_SHARED"}}, AllowedRoots: []string{"/var/log", "/etc", "/opt", "/tmp/aiops-demo-logs", "/var/log/aiops-demo"}, BlockedPaths: []string{"/", "/boot", "/var/lib/mysql", "/var/lib/postgresql", "/var/log/audit"}},
		{Name: "kylin.system.sysctl_snapshot", InternalName: "sysctl_snapshot", Description: "Read kernel sysctl parameters", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.system.mounts", InternalName: "filesystem_mounts", Description: "List mounted filesystems", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.package.list", InternalName: "list_installed_packages", Description: "List installed RPM packages", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.package.info", InternalName: "package_info", Description: "Read RPM package metadata", InputSchema: objectSchema(map[string]any{"package": stringSchema()}, []string{"package"}), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.user.accounts", InternalName: "list_user_accounts", Description: "List local user accounts", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.user.privileged_groups", InternalName: "list_privileged_groups", Description: "List common privileged group members", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.user.last_logins", InternalName: "last_logins", Description: "List recent login records", InputSchema: objectSchema(map[string]any{"limit": integerSchema(1, 200)}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.config.permissions_snapshot", InternalName: "config_permissions_snapshot", Description: "Read key configuration file permissions", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.diagnosis.triage", InternalName: "diagnosis_triage", Description: "Collect a read-only host triage summary", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.cleanup.plan", InternalName: "cleanup_plan", Description: "Plan archive and truncate cleanup for approved demo directories", InputSchema: objectSchema(map[string]any{"targetDir": stringSchema(), "maxFiles": integerSchema(1, 100)}, nil), RiskCode: "R0", ReadOnly: true, ResourceLocks: []ResourceLock{{ResourceKey: "path:${targetDir}", LockType: "READ_SHARED"}}, AllowedRoots: cleanupAllowedRoots(), BlockedPaths: []string{"/", "/etc", "/boot", "/usr", "/var/lib/mysql", "/var/lib/postgresql", "/var/log/audit"}},
		{Name: "kylin.cleanup.execute", InternalName: "cleanup_execute", Description: "Archive and truncate approved demo directories with rollback metadata", InputSchema: objectSchema(map[string]any{"targetDir": stringSchema(), "mode": enumSchema([]string{"archive", "archive_then_truncate"}), "maxFiles": integerSchema(1, 100)}, nil), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "path:${targetDir}", LockType: "WRITE_EXCLUSIVE"}}, AllowedRoots: cleanupAllowedRoots(), BlockedPaths: []string{"/", "/etc", "/boot", "/usr", "/var/lib/mysql", "/var/lib/postgresql", "/var/log/audit"}},
		{Name: "kylin.cleanup.rollback", InternalName: "cleanup_rollback", Description: "Restore a cleanup rollback package from rollback_id", InputSchema: objectSchema(map[string]any{"rollbackId": stringSchema()}, []string{"rollbackId"}), RiskCode: "R3", RequiresApproval: true, ReadOnly: false, ResourceLocks: []ResourceLock{{ResourceKey: "rollback:${rollbackId}", LockType: "WRITE_EXCLUSIVE"}}, AllowedRoots: cleanupAllowedRoots(), BlockedPaths: []string{"/", "/etc", "/boot", "/usr", "/var/lib/mysql", "/var/lib/postgresql", "/var/log/audit"}},
		{Name: "kylin.security.kysec_status", InternalName: "kysec_status", Description: "Read KYSEC security status when available", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.security.selinux_status", InternalName: "selinux_status", Description: "Read SELinux enforce/permissive/disabled status", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.security.auditd_status", InternalName: "auditd_status", Description: "Read auditd service status", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.security.security_switch_status", InternalName: "security_switch_status", Description: "Read Kylin security-switch status when available", InputSchema: objectSchema(map[string]any{}, nil), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.workspace.bash", InternalName: "workspace_bash", Description: "Execute a bash script inside the sandboxed workspace. The container has no network, no host access, and a read-only root filesystem.", InputSchema: objectSchema(map[string]any{"script": map[string]any{"type": "string", "minLength": 1, "maxLength": 100000}}, []string{"script"}), RiskCode: "R1", ReadOnly: false},
		{Name: "kylin.workspace.python", InternalName: "workspace_python", Description: "Execute a python3 script inside the sandboxed workspace. The container has no network, no host access, and a read-only root filesystem.", InputSchema: objectSchema(map[string]any{"script": map[string]any{"type": "string", "minLength": 1, "maxLength": 100000}}, []string{"script"}), RiskCode: "R1", ReadOnly: false},
		{Name: "kylin.workspace.read", InternalName: "workspace_read", Description: "Read a file from the sandboxed workspace filesystem. Path must be relative and within the workspace.", InputSchema: objectSchema(map[string]any{"path": map[string]any{"type": "string", "minLength": 1, "maxLength": 1024}}, []string{"path"}), RiskCode: "R0", ReadOnly: true},
		{Name: "kylin.workspace.write", InternalName: "workspace_write", Description: "Write a file in the sandboxed workspace filesystem. Path must be relative and within the workspace.", InputSchema: objectSchema(map[string]any{"path": map[string]any{"type": "string", "minLength": 1, "maxLength": 1024}, "content": map[string]any{"type": "string"}}, []string{"path", "content"}), RiskCode: "R1", ReadOnly: false},
		{Name: "kylin.workspace.edit", InternalName: "workspace_edit", Description: "Edit a file in the sandboxed workspace by replacing oldText with newText. Path must be relative and within the workspace.", InputSchema: objectSchema(map[string]any{"path": map[string]any{"type": "string", "minLength": 1, "maxLength": 1024}, "oldText": map[string]any{"type": "string"}, "newText": map[string]any{"type": "string"}}, []string{"path", "oldText", "newText"}), RiskCode: "R1", ReadOnly: false},
		{Name: "kylin.workspace.glob", InternalName: "workspace_glob", Description: "Glob for files in the sandboxed workspace filesystem. Pattern is relative to the workspace root.", InputSchema: objectSchema(map[string]any{"pattern": map[string]any{"type": "string", "minLength": 1, "maxLength": 1024}}, []string{"pattern"}), RiskCode: "R0", ReadOnly: true},
	} {
		if item.OutputSchema == nil {
			item.OutputSchema = defaultOutputSchema()
		}
		catalog.tools[item.Name] = item
	}
	return catalog
}

func normalizeRisk(value string) string {
	switch strings.ToUpper(strings.TrimSpace(value)) {
	case "", "LOW", "R0":
		return "R0"
	case "R1":
		return "R1"
	case "MEDIUM", "R2":
		return "R2"
	case "R3":
		return "R3"
	case "HIGH", "R4":
		return "R4"
	case "R5":
		return "R5"
	default:
		return ""
	}
}

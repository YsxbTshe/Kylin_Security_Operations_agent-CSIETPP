package executor

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"time"

	daemonconfig "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/config"
)

var serviceNamePattern = regexp.MustCompile(`^[a-zA-Z0-9@._-]+$`)
var packageNamePattern = regexp.MustCompile(`^[a-zA-Z0-9+@._:-]+$`)

type Service struct {
	executionUser           string
	allowedServices         map[string]struct{}
	allowedBlockScopes      map[string]struct{}
	allowedPathRoots        []string
	maxBlockDurationMinutes int
	maxCommandOutputBytes   int
	commandTimeout          time.Duration
	handlers                map[string]toolHandler
}

type ToolDefinition struct {
	Name        string
	Handler     string
	ReadOnly    bool
	Description string
}

type toolHandler struct {
	readOnly    bool
	description string
	build       func(map[string]string) (commandSpec, string, error)
}

type commandSpec struct {
	toolName string
	name     string
	args     []string
	// allowExitCodes are non-zero exit codes that still represent a valid result
	// (e.g. `systemctl status` returns 3 for an inactive/dead service — a real status,
	// not a tool failure). exit 4 (no such unit) is NOT listed and remains an error.
	allowExitCodes []int
	// needsRoot 标记真正需要特权的命令。执行器自身没有任何提权手段，
	// 这些命令由 safeops.Runner 路由到特权助手（ADR-002）；标记留在这里，
	// 是为了让本地路径能对它们 fail-closed 而不是以非特权身份跑出半截结果。
	//
	// 绝大多数只读诊断（df/free/ps/systemctl status/ss…）经实测无需特权，
	// 标记为 false 后直接以 kylin-agent 执行。
	needsRoot bool
}

func NewService(cfg daemonconfig.Config) Service {
	service := Service{
		executionUser:           strings.TrimSpace(cfg.ExecutionUser),
		allowedServices:         toLookup(cfg.AllowedServices),
		allowedBlockScopes:      toLookup(cfg.AllowedBlockScopes),
		allowedPathRoots:        append([]string(nil), cfg.AllowedPathRoots...),
		maxBlockDurationMinutes: cfg.MaxBlockDurationMinutes,
		maxCommandOutputBytes:   cfg.MaxCommandOutputBytes,
		commandTimeout:          time.Duration(cfg.CommandTimeoutSeconds) * time.Second,
	}
	if service.commandTimeout <= 0 {
		service.commandTimeout = 20 * time.Second
	}
	service.handlers = map[string]toolHandler{
		"restart_service":             {readOnly: false, description: "Restart an allowlisted service", build: service.buildRestartServiceCommand},
		"start_service":               {readOnly: false, description: "Start an allowlisted service", build: service.buildStartServiceCommand},
		"stop_service":                {readOnly: false, description: "Stop an allowlisted service", build: service.buildStopServiceCommand},
		"reload_service":              {readOnly: false, description: "Reload an allowlisted service", build: service.buildReloadServiceCommand},
		"enable_service":              {readOnly: false, description: "Enable an allowlisted service at boot", build: service.buildEnableServiceCommand},
		"disable_service":             {readOnly: false, description: "Disable an allowlisted service at boot", build: service.buildDisableServiceCommand},
		"service_status":              {readOnly: true, description: "Read status for an allowlisted service", build: service.buildServiceStatusCommand},
		"journalctl_query":            {readOnly: true, description: "Query journald logs for an allowlisted service", build: service.buildJournalctlCommand},
		"list_services":               {readOnly: true, description: "List systemd services", build: service.buildListServicesCommand},
		"list_failed_services":        {readOnly: true, description: "List failed systemd services", build: service.buildListFailedServicesCommand},
		"list_enabled_services":       {readOnly: true, description: "List enabled systemd services", build: service.buildListEnabledServicesCommand},
		"list_timers":                 {readOnly: true, description: "List systemd timers", build: service.buildListTimersCommand},
		"list_listening_ports":        {readOnly: true, description: "List listening ports", build: service.buildListeningPortsCommand},
		"list_connections":            {readOnly: true, description: "List active TCP/UDP connections", build: service.buildConnectionsCommand},
		"lsof_path":                   {readOnly: true, description: "Run lsof for an allowed path", build: service.buildLsofPathCommand},
		"process_list":                {readOnly: true, description: "List processes", build: service.buildProcessListCommand},
		"top_cpu_processes":           {readOnly: true, description: "Top CPU consuming processes", build: service.buildTopCPUProcessesCommand},
		"top_memory_processes":        {readOnly: true, description: "Top memory consuming processes", build: service.buildTopMemoryProcessesCommand},
		"disk_usage":                  {readOnly: true, description: "Read disk usage", build: service.buildDiskUsageCommand},
		"disk_large_files":            {readOnly: true, description: "Find large files under an allowed path"},
		"io_snapshot":                 {readOnly: true, description: "Read disk I/O counters"},
		"memory_usage":                {readOnly: true, description: "Read memory usage", build: service.buildMemoryUsageCommand},
		"load_average":                {readOnly: true, description: "Read load average", build: service.buildLoadAverageCommand},
		"uptime_status":               {readOnly: true, description: "Read host uptime", build: service.buildUptimeCommand},
		"logged_in_users":             {readOnly: true, description: "List logged in users", build: service.buildLoggedInUsersCommand},
		"filesystem_mounts":           {readOnly: true, description: "List mounted filesystems", build: service.buildFilesystemMountsCommand},
		"ip_addr_show":                {readOnly: true, description: "Show interface addresses", build: service.buildIPAddressCommand},
		"ip_route_show":               {readOnly: true, description: "Show routing table", build: service.buildIPRouteCommand},
		"kernel_info":                 {readOnly: true, description: "Read kernel information", build: service.buildKernelInfoCommand},
		"sysctl_snapshot":             {readOnly: true, description: "Read kernel sysctl parameters", build: service.buildSysctlSnapshotCommand},
		"list_installed_packages":     {readOnly: true, description: "List installed RPM packages", build: service.buildListInstalledPackagesCommand},
		"recent_package_changes":      {readOnly: true, description: "List recent RPM package install times", build: service.buildRecentPackageChangesCommand},
		"package_info":                {readOnly: true, description: "Read RPM package metadata", build: service.buildPackageInfoCommand},
		"list_user_accounts":          {readOnly: true, description: "List local user accounts", build: service.buildListUserAccountsCommand},
		"list_privileged_groups":      {readOnly: true, description: "List common privileged group members", build: service.buildListPrivilegedGroupsCommand},
		"last_logins":                 {readOnly: true, description: "List recent login records", build: service.buildLastLoginsCommand},
		"config_permissions_snapshot": {readOnly: true, description: "Read key configuration file permissions", build: service.buildConfigPermissionsSnapshotCommand},
		"file_stat":                   {readOnly: true, description: "Read metadata for an allowed file path", build: service.buildFileStatCommand},
		"file_sm3":                    {readOnly: true, description: "Read SM3 digest for an allowed file path", build: service.buildFileSM3Command},
		"firewall_state":              {readOnly: true, description: "Read firewalld runtime state", build: service.buildFirewallStateCommand},
		"firewall_rules":              {readOnly: true, description: "List firewalld public zone rules", build: service.buildFirewallRulesCommand},
		"block_ip":                    {readOnly: false, description: "Block an IP via firewalld", build: service.buildBlockIPCommand},
		"unblock_ip":                  {readOnly: false, description: "Unblock an IP via firewalld", build: service.buildUnblockIPCommand},
		"cleanup_plan":                {readOnly: true, description: "Plan archive and truncate cleanup for demo logs"},
		"cleanup_execute":             {readOnly: false, description: "Archive and truncate demo logs with rollback metadata"},
		"cleanup_rollback":            {readOnly: false, description: "Restore demo logs from rollback metadata"},
		"kysec_status":                {readOnly: true, description: "Read KYSEC security status when available"},
		"selinux_status":              {readOnly: true, description: "Read SELinux status"},
		"auditd_status":               {readOnly: true, description: "Read auditd service status"},
		"security_switch_status":      {readOnly: true, description: "Read Kylin security-switch status when available"},
	}
	return service
}

func (s Service) ListTools() []ToolDefinition {
	tools := make([]ToolDefinition, 0, len(s.handlers))
	for name, handler := range s.handlers {
		tools = append(tools, ToolDefinition{
			Name:        name,
			Handler:     name,
			ReadOnly:    handler.readOnly,
			Description: handler.description,
		})
	}
	slices.SortFunc(tools, func(left, right ToolDefinition) int {
		return strings.Compare(left.Name, right.Name)
	})
	return tools
}

func (s Service) Execute(toolName string, arguments map[string]string) (string, error) {
	resolvedName, mergedArgs := s.resolveTool(toolName, arguments)
	switch resolvedName {
	case "disk_large_files":
		return s.diskLargeFiles(mergedArgs)
	case "io_snapshot":
		return s.ioSnapshot(mergedArgs)
	case "cleanup_plan":
		return s.cleanupPlan(mergedArgs)
	case "cleanup_execute", "cleanup_rollback":
		// 执行/回滚已下沉到特权助手（ADR-002 §4.2）。到这里说明有调用路径绕过了
		// safeops 的助手路由——必须失败，而不是以非特权身份在本地跑一个半截的写操作。
		return "", fmt.Errorf("%s 须经特权助手执行，不允许在 Agent 进程内直接运行", resolvedName)
	case "kysec_status":
		return s.kysecStatus()
	case "selinux_status":
		return s.selinuxStatus()
	case "auditd_status":
		return s.auditdStatus()
	case "security_switch_status":
		return s.securitySwitchStatus()
	}
	handler, ok := s.handlers[resolvedName]
	if !ok {
		return "", fmt.Errorf("unsupported tool: %s", strings.TrimSpace(toolName))
	}

	spec, summary, err := handler.build(mergedArgs)
	if err != nil {
		return "", err
	}
	output, err := s.run(spec)
	if err != nil {
		return "", err
	}
	if resolvedName == "service_status" {
		return structuredServiceStatusResult(mergedArgs["service"], output)
	}
	return s.commandResult(resolvedName, handler.readOnly, summary, output)
}

func (s Service) resolveTool(toolName string, arguments map[string]string) (string, map[string]string) {
	resolved := strings.TrimSpace(toolName)
	merged := map[string]string{}
	for key, value := range arguments {
		merged[key] = strings.TrimSpace(value)
	}
	return resolved, merged
}

// commandResult 把一次命令的成功执行结果统一成形。
//
// 写工具旧链路里的 rollback_plan/rollback_verify 载荷已随旧回滚链退役：
// 一切写操作都经特权助手执行，清理回滚包由助手侧的 cleanupcore 管理，
// daemon 不再自行编造回滚计划。
func (Service) commandResult(toolName string, readOnly bool, summary string, output string) (string, error) {
	if readOnly {
		return plainCommandResult(summary, output), nil
	}
	return jsonResult(map[string]any{
		"tool":    toolName,
		"status":  "EXECUTED",
		"summary": summary,
		"output":  output,
	})
}

func plainCommandResult(summary string, output string) string {
	if output == "" {
		return summary
	}
	if summary == "" {
		return output
	}
	return summary + "\n" + output
}

func (s Service) buildRestartServiceCommand(arguments map[string]string) (commandSpec, string, error) {
	return s.buildServiceActionCommand("restart_service", "restart", arguments)
}

func (s Service) buildStartServiceCommand(arguments map[string]string) (commandSpec, string, error) {
	return s.buildServiceActionCommand("start_service", "start", arguments)
}

func (s Service) buildStopServiceCommand(arguments map[string]string) (commandSpec, string, error) {
	return s.buildServiceActionCommand("stop_service", "stop", arguments)
}

func (s Service) buildReloadServiceCommand(arguments map[string]string) (commandSpec, string, error) {
	return s.buildServiceActionCommand("reload_service", "reload", arguments)
}

func (s Service) buildEnableServiceCommand(arguments map[string]string) (commandSpec, string, error) {
	return s.buildServiceActionCommand("enable_service", "enable", arguments)
}

func (s Service) buildDisableServiceCommand(arguments map[string]string) (commandSpec, string, error) {
	return s.buildServiceActionCommand("disable_service", "disable", arguments)
}

func (s Service) buildServiceStatusCommand(arguments map[string]string) (commandSpec, string, error) {
	serviceName, err := s.requireAllowedService(arguments["service"])
	if err != nil {
		return commandSpec{}, "", err
	}
	return commandSpec{
		toolName: "service_status",
		name:     "systemctl",
		args: []string{"show", serviceName, "--no-pager",
			"--property=ActiveState,SubState,LoadState,UnitFileState"},
	}, fmt.Sprintf("service %s status", serviceName), nil
}

func structuredServiceStatusResult(serviceName string, output string) (string, error) {
	values := map[string]string{}
	for _, line := range strings.Split(output, "\n") {
		key, value, found := strings.Cut(strings.TrimSpace(line), "=")
		if !found {
			continue
		}
		values[strings.TrimSpace(key)] = strings.TrimSpace(value)
	}
	result := map[string]string{
		"service":       strings.TrimSpace(serviceName),
		"activeState":   values["ActiveState"],
		"subState":      values["SubState"],
		"loadState":     values["LoadState"],
		"unitFileState": values["UnitFileState"],
	}
	for key, value := range result {
		if value == "" {
			return "", fmt.Errorf("service_status missing systemd property %s", key)
		}
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		return "", fmt.Errorf("serialize service_status result: %w", err)
	}
	return string(encoded), nil
}

func (s Service) buildJournalctlCommand(arguments map[string]string) (commandSpec, string, error) {
	serviceName, err := s.requireAllowedService(arguments["unit"])
	if err != nil {
		return commandSpec{}, "", err
	}
	lines := normalizePositiveInt(arguments["lines"], 100, 500)
	args := []string{"-u", serviceName, "-n", strconv.Itoa(lines), "--no-pager", "--output=short"}
	priority := strings.ToLower(strings.TrimSpace(arguments["priority"]))
	if priority != "" {
		args = append(args, "-p", priority)
	}
	return commandSpec{
		toolName: "journalctl_query",
		name:     "journalctl",
		args:     args,
		// 不需要特权：daemon unit 的 SupplementaryGroups=systemd-journal 已给到
		// 读取全量日志的资格。用组成员身份而不是提权，是这里最便宜也最干净的解法。
		needsRoot: false,
	}, fmt.Sprintf("journalctl for %s", serviceName), nil
}

func (s Service) buildListServicesCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "list_services",
		name:     "systemctl",
		args:     []string{"list-units", "--type=service", "--all", "--no-pager", "--no-legend"},
	}, "listed services", nil
}

func (s Service) buildListFailedServicesCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "list_failed_services",
		name:     "systemctl",
		args:     []string{"list-units", "--type=service", "--failed", "--no-pager", "--no-legend"},
	}, "listed failed services", nil
}

func (s Service) buildListEnabledServicesCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "list_enabled_services",
		name:     "systemctl",
		args:     []string{"list-unit-files", "--type=service", "--state=enabled", "--no-pager", "--no-legend"},
	}, "listed enabled services", nil
}

func (s Service) buildListTimersCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "list_timers",
		name:     "systemctl",
		args:     []string{"list-timers", "--all", "--no-pager", "--no-legend"},
	}, "listed timers", nil
}

func (s Service) buildListeningPortsCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "list_listening_ports",
		name:     "ss",
		args:     []string{"-tulpn"},
		// 以非特权身份运行会缺少其它用户的进程名，端口与连接状态照常可见。
		// 补齐它需要 CAP_SYS_PTRACE，那等价于可读任意进程内存——为一列进程名
		// 换这个能力不划算，接受降级（ADR-002 §3.6）。
		needsRoot: false,
	}, "listed listening ports", nil
}

func (s Service) buildConnectionsCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "list_connections",
		name:     "ss",
		args:     []string{"-tunap"},
		// 以非特权身份运行会缺少其它用户的进程名，端口与连接状态照常可见。
		// 补齐它需要 CAP_SYS_PTRACE，那等价于可读任意进程内存——为一列进程名
		// 换这个能力不划算，接受降级（ADR-002 §3.6）。
		needsRoot: false,
	}, "listed connections", nil
}

func (s Service) buildLsofPathCommand(arguments map[string]string) (commandSpec, string, error) {
	pathValue, err := s.requireAllowedAbsolutePath("lsof_path", arguments["path"])
	if err != nil {
		return commandSpec{}, "", err
	}
	return commandSpec{
		toolName: "lsof_path",
		name:     "lsof",
		args:     []string{pathValue},
		// 以非特权身份运行会缺少其它用户的进程名，端口与连接状态照常可见。
		// 补齐它需要 CAP_SYS_PTRACE，那等价于可读任意进程内存——为一列进程名
		// 换这个能力不划算，接受降级（ADR-002 §3.6）。
		needsRoot: false,
	}, fmt.Sprintf("lsof for %s", pathValue), nil
}

func (s Service) buildProcessListCommand(arguments map[string]string) (commandSpec, string, error) {
	limit := normalizePositiveInt(arguments["limit"], 20, 200)
	return commandSpec{
		toolName: "process_list",
		name:     "ps",
		args:     []string{"-eo", "pid,ppid,user,%cpu,%mem,comm", "--sort=-%cpu"},
	}, fmt.Sprintf("process list top %d", limit), nil
}

func (s Service) buildTopCPUProcessesCommand(arguments map[string]string) (commandSpec, string, error) {
	limit := normalizePositiveInt(arguments["limit"], 10, 100)
	return commandSpec{
		toolName: "top_cpu_processes",
		name:     "ps",
		args:     []string{"-eo", "pid,user,%cpu,%mem,comm", "--sort=-%cpu"},
	}, fmt.Sprintf("top cpu processes limit %d", limit), nil
}

func (s Service) buildTopMemoryProcessesCommand(arguments map[string]string) (commandSpec, string, error) {
	limit := normalizePositiveInt(arguments["limit"], 10, 100)
	return commandSpec{
		toolName: "top_memory_processes",
		name:     "ps",
		args:     []string{"-eo", "pid,user,%mem,%cpu,comm", "--sort=-%mem"},
	}, fmt.Sprintf("top memory processes limit %d", limit), nil
}

func (s Service) buildDiskUsageCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "disk_usage",
		name:     "df",
		args:     []string{"-h"},
	}, "disk usage", nil
}

func (s Service) buildMemoryUsageCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "memory_usage",
		name:     "free",
		args:     []string{"-m"},
	}, "memory usage", nil
}

func (s Service) buildLoadAverageCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "load_average",
		name:     "uptime",
		args:     []string{},
	}, "load average", nil
}

func (s Service) buildUptimeCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "uptime_status",
		name:     "uptime",
		args:     []string{"-p"},
	}, "uptime status", nil
}

func (s Service) buildLoggedInUsersCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "logged_in_users",
		name:     "who",
		args:     []string{},
	}, "logged in users", nil
}

func (s Service) buildFilesystemMountsCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "filesystem_mounts",
		name:     "findmnt",
		args:     []string{"-lo", "TARGET,SOURCE,FSTYPE,OPTIONS"},
	}, "filesystem mounts", nil
}

func (s Service) buildIPAddressCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "ip_addr_show",
		name:     "ip",
		args:     []string{"addr", "show"},
	}, "ip addr show", nil
}

func (s Service) buildIPRouteCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "ip_route_show",
		name:     "ip",
		args:     []string{"route", "show"},
	}, "ip route show", nil
}

func (s Service) buildKernelInfoCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "kernel_info",
		name:     "uname",
		args:     []string{"-a"},
	}, "kernel info", nil
}

func (s Service) buildSysctlSnapshotCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "sysctl_snapshot",
		name:     "sysctl",
		args:     []string{"-a"},
	}, "sysctl snapshot", nil
}

func (s Service) buildListInstalledPackagesCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "list_installed_packages",
		name:     "rpm",
		args:     []string{"-qa", "--queryformat", "%{NAME} %{VERSION}-%{RELEASE} %{ARCH}\n"},
	}, "installed package inventory", nil
}

func (s Service) buildRecentPackageChangesCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "recent_package_changes",
		name:     "rpm",
		args:     []string{"-qa", "--last"},
	}, "recent package changes", nil
}

func (s Service) buildPackageInfoCommand(arguments map[string]string) (commandSpec, string, error) {
	packageName := strings.TrimSpace(arguments["package"])
	if packageName == "" {
		return commandSpec{}, "", fmt.Errorf("package_info requires package")
	}
	if !packageNamePattern.MatchString(packageName) {
		return commandSpec{}, "", fmt.Errorf("package contains invalid characters")
	}
	return commandSpec{
		toolName: "package_info",
		name:     "rpm",
		args:     []string{"-qi", packageName},
	}, fmt.Sprintf("package %s info", packageName), nil
}

func (s Service) buildListUserAccountsCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "list_user_accounts",
		name:     "getent",
		args:     []string{"passwd"},
	}, "local user accounts", nil
}

func (s Service) buildListPrivilegedGroupsCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "list_privileged_groups",
		name:     "getent",
		args:     []string{"group", "root", "wheel", "sudo"},
	}, "privileged group members", nil
}

func (s Service) buildLastLoginsCommand(arguments map[string]string) (commandSpec, string, error) {
	limit := normalizePositiveInt(arguments["limit"], 20, 200)
	return commandSpec{
		toolName: "last_logins",
		name:     "last",
		args:     []string{"-n", strconv.Itoa(limit), "-w"},
	}, fmt.Sprintf("recent login records limit %d", limit), nil
}

func (s Service) buildConfigPermissionsSnapshotCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName: "config_permissions_snapshot",
		name:     "stat",
		args: []string{
			"-c",
			"%a %U %G %n",
			"--",
			"/etc/passwd",
			"/etc/shadow",
			"/etc/group",
		},
	}, "key configuration permissions snapshot", nil
}

func (s Service) buildFileStatCommand(arguments map[string]string) (commandSpec, string, error) {
	pathValue, err := s.requireAllowedAbsolutePath("file_stat", arguments["path"])
	if err != nil {
		return commandSpec{}, "", err
	}
	return commandSpec{
		toolName: "file_stat",
		name:     "stat",
		args:     []string{"--", pathValue},
	}, fmt.Sprintf("file metadata for %s", pathValue), nil
}

func (s Service) buildFileSM3Command(arguments map[string]string) (commandSpec, string, error) {
	pathValue, err := s.requireAllowedAbsolutePath("file_sm3", arguments["path"])
	if err != nil {
		return commandSpec{}, "", err
	}
	return commandSpec{
		toolName: "file_sm3",
		name:     "openssl",
		args:     []string{"dgst", "-sm3", "-r", pathValue},
	}, fmt.Sprintf("file sm3 for %s", pathValue), nil
}

func (s Service) buildFirewallStateCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName:  "firewall_state",
		name:      "firewall-cmd",
		args:      []string{"--state"},
		needsRoot: true,
	}, "firewalld state", nil
}

func (s Service) buildFirewallRulesCommand(map[string]string) (commandSpec, string, error) {
	return commandSpec{
		toolName:  "firewall_rules",
		name:      "firewall-cmd",
		args:      []string{"--zone=public", "--list-all"},
		needsRoot: true,
	}, "firewalld public zone rules", nil
}

func (s Service) buildBlockIPCommand(arguments map[string]string) (commandSpec, string, error) {
	ipAddress := strings.TrimSpace(arguments["ip"])
	if net.ParseIP(ipAddress) == nil {
		return commandSpec{}, "", fmt.Errorf("block_ip requires a valid ip")
	}
	scope, durationValue, err := s.validateBlockArguments(arguments)
	if err != nil {
		return commandSpec{}, "", err
	}

	family := "ipv6"
	if parsed := net.ParseIP(ipAddress); parsed != nil && parsed.To4() != nil {
		family = "ipv4"
	}
	richRule := fmt.Sprintf("rule family=\"%s\" source address=\"%s\" drop", family, ipAddress)

	return commandSpec{
		toolName: "block_ip",
		name:     "firewall-cmd",
		args: []string{
			"--quiet",
			"--add-rich-rule=" + richRule,
			"--timeout=" + durationValue,
		},
		needsRoot: true,
	}, fmt.Sprintf("ip %s blocked on scope %s for %s", ipAddress, scope, durationValue), nil
}

func (s Service) buildUnblockIPCommand(arguments map[string]string) (commandSpec, string, error) {
	ipAddress := strings.TrimSpace(arguments["ip"])
	if net.ParseIP(ipAddress) == nil {
		return commandSpec{}, "", fmt.Errorf("unblock_ip requires a valid ip")
	}
	scope := strings.ToLower(strings.TrimSpace(arguments["scope"]))
	if scope == "" {
		scope = "ingress"
	}
	if _, ok := s.allowedBlockScopes[scope]; !ok {
		return commandSpec{}, "", fmt.Errorf("unblock_ip rejected: scope %s is not in allowlist", scope)
	}
	family := "ipv6"
	if parsed := net.ParseIP(ipAddress); parsed != nil && parsed.To4() != nil {
		family = "ipv4"
	}
	richRule := fmt.Sprintf("rule family=\"%s\" source address=\"%s\" drop", family, ipAddress)
	return commandSpec{
		toolName: "unblock_ip",
		name:     "firewall-cmd",
		args: []string{
			"--quiet",
			"--remove-rich-rule=" + richRule,
		},
		needsRoot: true,
	}, fmt.Sprintf("ip %s unblocked on scope %s", ipAddress, scope), nil
}

func (s Service) buildServiceActionCommand(toolName string, action string, arguments map[string]string) (commandSpec, string, error) {
	serviceName, err := s.requireAllowedService(arguments["service"])
	if err != nil {
		return commandSpec{}, "", err
	}
	return commandSpec{
		toolName:  toolName,
		name:      "systemctl",
		args:      []string{action, serviceName},
		needsRoot: true,
	}, fmt.Sprintf("service %s %sed by node executor under %s", serviceName, action, s.describeExecutionIdentity()), nil
}

func (s Service) requireAllowedService(value string) (string, error) {
	serviceName := strings.TrimSpace(value)
	if serviceName == "" {
		return "", fmt.Errorf("service argument is required")
	}
	if !serviceNamePattern.MatchString(serviceName) {
		return "", fmt.Errorf("service contains invalid characters")
	}
	if _, ok := s.allowedServices[serviceName]; !ok {
		return "", fmt.Errorf("service %s is not in allowlist", serviceName)
	}
	return serviceName, nil
}

func (s Service) requireAllowedAbsolutePath(toolName string, value string) (string, error) {
	pathValue := strings.TrimSpace(value)
	if pathValue == "" {
		return "", fmt.Errorf("%s requires path", toolName)
	}
	cleanPath := cleanLinuxPath(pathValue)
	if !strings.HasPrefix(cleanPath, "/") {
		return "", fmt.Errorf("%s requires absolute path", toolName)
	}
	if !s.isAllowedPath(cleanPath) {
		return "", fmt.Errorf("%s rejected: path %s is outside allowed roots", toolName, cleanPath)
	}
	return cleanPath, nil
}

func (s Service) validateBlockArguments(arguments map[string]string) (string, string, error) {
	scope := strings.ToLower(strings.TrimSpace(arguments["scope"]))
	if scope == "" {
		return "", "", fmt.Errorf("block_ip requires scope")
	}
	if _, ok := s.allowedBlockScopes[scope]; !ok {
		return "", "", fmt.Errorf("block_ip rejected: scope %s is not in allowlist", scope)
	}
	if scope != "ingress" {
		return "", "", fmt.Errorf("block_ip currently supports ingress only")
	}
	durationValue := strings.TrimSpace(arguments["duration"])
	if durationValue == "" {
		return "", "", fmt.Errorf("block_ip requires duration")
	}
	duration, err := time.ParseDuration(durationValue)
	if err != nil {
		return "", "", fmt.Errorf("block_ip duration is invalid")
	}
	if duration < time.Minute {
		return "", "", fmt.Errorf("block_ip duration must be at least 1m")
	}
	if s.maxBlockDurationMinutes > 0 && duration > time.Duration(s.maxBlockDurationMinutes)*time.Minute {
		return "", "", fmt.Errorf("block_ip duration exceeds local policy limit")
	}
	return scope, durationValue, nil
}

func (s Service) isAllowedPath(pathValue string) bool {
	for _, root := range s.allowedPathRoots {
		root = cleanLinuxPath(root)
		if root == "" {
			continue
		}
		if pathValue == root || strings.HasPrefix(pathValue, strings.TrimRight(root, "/")+"/") {
			return true
		}
	}
	return false
}

func cleanLinuxPath(pathValue string) string {
	return filepath.ToSlash(filepath.Clean(strings.TrimSpace(pathValue)))
}

// UnsupportedPathRoots 返回 roots 中不被执行器底线覆盖的那些。
// 工具目录里每个工具自带 AllowedRoots，执行器另有一份全局底线，两者都要过。
// 目录若宣称了底线之外的根路径，工具会在目录里显示可用、执行时却被拒——
// 这种不一致只能在运行时以「rejected: outside allowed roots」暴露，很难归因。
func (s Service) UnsupportedPathRoots(roots []string) []string {
	var unsupported []string
	for _, root := range roots {
		if !s.isAllowedPath(cleanLinuxPath(root)) {
			unsupported = append(unsupported, root)
		}
	}
	return unsupported
}

func (s Service) run(spec commandSpec) (string, error) {
	if runtime.GOOS != "linux" {
		return "", fmt.Errorf("%s is only supported on linux hosts", spec.toolName)
	}

	if spec.needsRoot {
		// 执行器以非特权的 kylin-agent 运行，且不再有任何提权手段（sudo 已彻底移除，
		// 见 ADR-002）。需要特权的工具由 safeops.Runner 在更上层路由到特权助手，
		// 根本不会走到这里。真到了这里说明有新调用路径绕过了那次路由——
		// 必须失败，而不是以非特权身份执行一个半截生效的写操作。
		return "", fmt.Errorf("%s 须经特权助手执行，不允许在 Agent 进程内直接运行", spec.toolName)
	}
	commandName := spec.name
	commandArgs := spec.args

	ctx, cancel := context.WithTimeout(context.Background(), s.commandTimeout)
	defer cancel()

	command := exec.CommandContext(ctx, commandName, commandArgs...)
	output, err := command.CombinedOutput()
	if ctx.Err() == context.DeadlineExceeded {
		return "", fmt.Errorf("%s execution timed out after %s", spec.toolName, s.commandTimeout)
	}
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			for _, allowed := range spec.allowExitCodes {
				if exitErr.ExitCode() == allowed {
					return truncateOutput(strings.TrimSpace(string(output)), s.maxCommandOutputBytes), nil
				}
			}
		}
		message := strings.TrimSpace(string(output))
		if message == "" {
			message = err.Error()
		}
		return "", fmt.Errorf("%s execution failed: %s", spec.toolName, message)
	}
	return truncateOutput(strings.TrimSpace(string(output)), s.maxCommandOutputBytes), nil
}

func (s Service) describeExecutionIdentity() string {
	user := s.executionUser
	if user == "" {
		user = "current-user"
	}
	return user
}

func truncateOutput(value string, maxBytes int) string {
	if maxBytes <= 0 || len(value) <= maxBytes {
		return value
	}
	return value[:maxBytes] + "\n...truncated..."
}

func normalizePositiveInt(value string, fallback int, max int) int {
	parsed, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || parsed <= 0 {
		return fallback
	}
	if max > 0 && parsed > max {
		return max
	}
	return parsed
}

func toLookup(values []string) map[string]struct{} {
	result := make(map[string]struct{}, len(values))
	for _, value := range values {
		normalized := strings.TrimSpace(value)
		if normalized != "" {
			result[normalized] = struct{}{}
		}
	}
	return result
}

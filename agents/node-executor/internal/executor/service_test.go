package executor

import (
	"encoding/json"
	"testing"
	"time"

	daemonconfig "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/config"
)

func newTestService() Service {
	return NewService(daemonconfig.Config{
		ExecutionUser:           "kylin-agent",
		AllowedServices:         []string{"nginx", "sshd"},
		AllowedBlockScopes:      []string{"ingress"},
		AllowedPathRoots:        []string{"/var/log", "/etc"},
		MaxBlockDurationMinutes: 60,
		MaxCommandOutputBytes:   4096,
		CommandTimeoutSeconds:   20,
	})
}

func TestBuildRestartServiceCommandRejectsServiceOutsideAllowlist(t *testing.T) {
	service := newTestService()

	_, _, err := service.buildRestartServiceCommand(map[string]string{"service": "redis"})
	if err == nil {
		t.Fatalf("expected allowlist validation error")
	}
}

func TestBuildEnableServiceCommandBuildsSystemctlAction(t *testing.T) {
	service := newTestService()

	spec, summary, err := service.buildEnableServiceCommand(map[string]string{"service": "nginx"})
	if err != nil {
		t.Fatalf("expected command build success, got %v", err)
	}
	if spec.name != "systemctl" || len(spec.args) != 2 || spec.args[0] != "enable" || spec.args[1] != "nginx" {
		t.Fatalf("unexpected enable service command: %+v", spec)
	}
	if summary == "" {
		t.Fatalf("expected summary to be filled")
	}
}

func TestBuildDisableServiceCommandRejectsServiceOutsideAllowlist(t *testing.T) {
	service := newTestService()

	_, _, err := service.buildDisableServiceCommand(map[string]string{"service": "redis"})
	if err == nil {
		t.Fatalf("expected allowlist validation error")
	}
}

func TestBuildBlockIPCommandRejectsInvalidIP(t *testing.T) {
	service := newTestService()

	_, _, err := service.buildBlockIPCommand(map[string]string{
		"ip":       "not-an-ip",
		"scope":    "ingress",
		"duration": "15m",
	})
	if err == nil {
		t.Fatalf("expected invalid ip error")
	}
}

func TestBuildBlockIPCommandBuildsFirewalldCommand(t *testing.T) {
	service := newTestService()

	spec, summary, err := service.buildBlockIPCommand(map[string]string{
		"ip":       "192.168.10.66",
		"scope":    "ingress",
		"duration": "15m",
	})
	if err != nil {
		t.Fatalf("expected command build success, got %v", err)
	}
	if spec.name != "firewall-cmd" {
		t.Fatalf("expected firewall-cmd, got %s", spec.name)
	}
	if summary == "" {
		t.Fatalf("expected summary to be filled")
	}
}

// 写工具结果不再携带 rollback_plan/rollback_verify 载荷——旧回滚链已退役，
// 写操作经特权助手执行，清理回滚包由助手侧 cleanupcore 管理。
func TestWriteCommandResultCarriesNoRollbackMetadata(t *testing.T) {
	service := newTestService()

	output, err := service.commandResult("stop_service", false, "service nginx stopped", "")
	if err != nil {
		t.Fatalf("format write result: %v", err)
	}
	var parsed map[string]any
	if err := json.Unmarshal([]byte(output), &parsed); err != nil {
		t.Fatalf("expected write result JSON, got %s", output)
	}
	if parsed["tool"] != "stop_service" || parsed["status"] != "EXECUTED" {
		t.Fatalf("unexpected write result payload: %s", output)
	}
	for _, retired := range []string{"rollback_id", "rollback_plan", "rollback_execute", "rollback_verify", "rollback_failure_advice"} {
		if _, present := parsed[retired]; present {
			t.Fatalf("write result must not carry retired rollback field %q: %s", retired, output)
		}
	}
}

func TestServiceStatusResultIsStrictStructuredJSON(t *testing.T) {
	service := newTestService()

	output, err := structuredServiceStatusResult("nginx", "ActiveState=active\nSubState=running\nLoadState=loaded\nUnitFileState=enabled\n")
	if err != nil {
		t.Fatalf("format service status result: %v", err)
	}
	var parsed map[string]any
	if err := json.Unmarshal([]byte(output), &parsed); err != nil {
		t.Fatalf("expected structured JSON, got %q: %v", output, err)
	}
	if len(parsed) != 5 || parsed["service"] != "nginx" || parsed["activeState"] != "active" || parsed["subState"] != "running" || parsed["loadState"] != "loaded" || parsed["unitFileState"] != "enabled" {
		t.Fatalf("unexpected service status result: %v", parsed)
	}
	_ = service
}

func TestBuildLsofPathRejectsPathOutsideAllowRoots(t *testing.T) {
	service := newTestService()

	_, _, err := service.buildLsofPathCommand(map[string]string{"path": "/tmp/demo"})
	if err == nil {
		t.Fatalf("expected path allowlist validation error")
	}
}

func TestBuildFileSM3CommandRejectsPathOutsideAllowRoots(t *testing.T) {
	service := newTestService()

	_, _, err := service.buildFileSM3Command(map[string]string{"path": "/tmp/demo"})
	if err == nil {
		t.Fatalf("expected path allowlist validation error")
	}
}

func TestBuildFileSM3CommandBuildsDigestCommand(t *testing.T) {
	service := newTestService()

	spec, summary, err := service.buildFileSM3Command(map[string]string{"path": "/etc/ssh/sshd_config"})
	if err != nil {
		t.Fatalf("expected command build success, got %v", err)
	}
	if spec.name != "openssl" || len(spec.args) != 4 || spec.args[0] != "dgst" || spec.args[1] != "-sm3" || spec.args[2] != "-r" || spec.args[3] != "/etc/ssh/sshd_config" {
		t.Fatalf("unexpected file sm3 command: %+v", spec)
	}
	if summary != "file sm3 for /etc/ssh/sshd_config" {
		t.Fatalf("unexpected summary: %s", summary)
	}
}

func TestBuildListEnabledServicesCommandBuildsSystemctlQuery(t *testing.T) {
	service := newTestService()

	spec, summary, err := service.buildListEnabledServicesCommand(map[string]string{})
	if err != nil {
		t.Fatalf("expected command build success, got %v", err)
	}
	if spec.name != "systemctl" || len(spec.args) != 5 || spec.args[0] != "list-unit-files" || spec.args[2] != "--state=enabled" {
		t.Fatalf("unexpected enabled services command: %+v", spec)
	}
	if summary != "listed enabled services" {
		t.Fatalf("unexpected summary: %s", summary)
	}
}

func TestBuildFirewallRulesCommandBuildsFirewalldQuery(t *testing.T) {
	service := newTestService()

	spec, summary, err := service.buildFirewallRulesCommand(map[string]string{})
	if err != nil {
		t.Fatalf("expected command build success, got %v", err)
	}
	if spec.name != "firewall-cmd" || len(spec.args) != 2 || spec.args[0] != "--zone=public" || spec.args[1] != "--list-all" {
		t.Fatalf("unexpected firewall rules command: %+v", spec)
	}
	if summary != "firewalld public zone rules" {
		t.Fatalf("unexpected summary: %s", summary)
	}
}

func TestBuildPackageInfoCommandRejectsInvalidPackageName(t *testing.T) {
	service := newTestService()

	_, _, err := service.buildPackageInfoCommand(map[string]string{"package": "nginx;rm"})
	if err == nil {
		t.Fatalf("expected package name validation error")
	}
}

func TestBuildPackageInfoCommandBuildsRPMQuery(t *testing.T) {
	service := newTestService()

	spec, summary, err := service.buildPackageInfoCommand(map[string]string{"package": "nginx"})
	if err != nil {
		t.Fatalf("expected command build success, got %v", err)
	}
	if spec.name != "rpm" || len(spec.args) != 2 || spec.args[0] != "-qi" || spec.args[1] != "nginx" {
		t.Fatalf("unexpected package info command: %+v", spec)
	}
	if summary != "package nginx info" {
		t.Fatalf("unexpected summary: %s", summary)
	}
}

func TestBuildLastLoginsCommandNormalizesLimit(t *testing.T) {
	service := newTestService()

	spec, summary, err := service.buildLastLoginsCommand(map[string]string{"limit": "999"})
	if err != nil {
		t.Fatalf("expected command build success, got %v", err)
	}
	if spec.name != "last" || len(spec.args) != 3 || spec.args[0] != "-n" || spec.args[1] != "200" || spec.args[2] != "-w" {
		t.Fatalf("unexpected last logins command: %+v", spec)
	}
	if summary != "recent login records limit 200" {
		t.Fatalf("unexpected summary: %s", summary)
	}
}

func TestBuildSysctlSnapshotCommandBuildsReadOnlyQuery(t *testing.T) {
	service := newTestService()

	spec, summary, err := service.buildSysctlSnapshotCommand(map[string]string{})
	if err != nil {
		t.Fatalf("expected command build success, got %v", err)
	}
	if spec.name != "sysctl" || len(spec.args) != 1 || spec.args[0] != "-a" {
		t.Fatalf("unexpected sysctl snapshot command: %+v", spec)
	}
	if summary != "sysctl snapshot" {
		t.Fatalf("unexpected summary: %s", summary)
	}
}

func TestBuildConfigPermissionsSnapshotCommandBuildsFixedStatQuery(t *testing.T) {
	service := newTestService()

	spec, summary, err := service.buildConfigPermissionsSnapshotCommand(map[string]string{})
	if err != nil {
		t.Fatalf("expected command build success, got %v", err)
	}
	expectedArgs := []string{"-c", "%a %U %G %n", "--", "/etc/passwd", "/etc/shadow", "/etc/group"}
	if spec.name != "stat" || len(spec.args) != len(expectedArgs) {
		t.Fatalf("unexpected config permissions command: %+v", spec)
	}
	for index, expected := range expectedArgs {
		if spec.args[index] != expected {
			t.Fatalf("unexpected arg %d: got %s want %s", index, spec.args[index], expected)
		}
	}
	if summary != "key configuration permissions snapshot" {
		t.Fatalf("unexpected summary: %s", summary)
	}
}

func TestNewServiceDefaultsCommandTimeout(t *testing.T) {
	service := NewService(daemonconfig.Config{})

	if service.commandTimeout != 20*time.Second {
		t.Fatalf("expected default command timeout, got %s", service.commandTimeout)
	}
}

func TestListToolsIncludesExtendedBuiltins(t *testing.T) {
	service := newTestService()

	expected := map[string]bool{
		"block_ip":                    false,
		"cleanup_execute":             false,
		"cleanup_plan":                true,
		"cleanup_rollback":            false,
		"config_permissions_snapshot": true,
		"disable_service":             false,
		"disk_large_files":            true,
		"disk_usage":                  true,
		"enable_service":              false,
		"file_sm3":                    true,
		"file_stat":                   true,
		"filesystem_mounts":           true,
		"firewall_rules":              true,
		"firewall_state":              true,
		"ip_addr_show":                true,
		"ip_route_show":               true,
		"io_snapshot":                 true,
		"journalctl_query":            true,
		"kernel_info":                 true,
		"kysec_status":                true,
		"last_logins":                 true,
		"list_connections":            true,
		"list_enabled_services":       true,
		"list_failed_services":        true,
		"list_installed_packages":     true,
		"list_listening_ports":        true,
		"list_privileged_groups":      true,
		"list_services":               true,
		"list_timers":                 true,
		"list_user_accounts":          true,
		"load_average":                true,
		"logged_in_users":             true,
		"lsof_path":                   true,
		"memory_usage":                true,
		"package_info":                true,
		"process_list":                true,
		"recent_package_changes":      true,
		"reload_service":              false,
		"restart_service":             false,
		"auditd_status":               true,
		"security_switch_status":      true,
		"service_status":              true,
		"selinux_status":              true,
		"start_service":               false,
		"stop_service":                false,
		"sysctl_snapshot":             true,
		"top_cpu_processes":           true,
		"top_memory_processes":        true,
		"unblock_ip":                  false,
		"uptime_status":               true,
	}
	tools := service.ListTools()
	if len(tools) != len(expected) {
		t.Fatalf("expected %d builtin tools, got %d", len(expected), len(tools))
	}
	for _, item := range service.ListTools() {
		readOnly, ok := expected[item.Name]
		if !ok {
			t.Fatalf("unexpected builtin tool listed: %s", item.Name)
		}
		if item.Handler != item.Name {
			t.Fatalf("expected builtin tool %s to use same handler, got %s", item.Name, item.Handler)
		}
		if item.ReadOnly != readOnly {
			t.Fatalf("expected builtin tool %s readOnly=%v, got %v", item.Name, readOnly, item.ReadOnly)
		}
	}
}

func TestNativeGuardStatusToolsReturnJSON(t *testing.T) {
	service := newTestService()

	output, err := service.Execute("selinux_status", map[string]string{})
	if err != nil {
		t.Fatalf("selinux status should not fail: %v", err)
	}
	var parsed map[string]any
	if err := json.Unmarshal([]byte(output), &parsed); err != nil {
		t.Fatalf("expected native guard JSON output, got %s", output)
	}
	if parsed["tool"] != "kylin.security.selinux_status" {
		t.Fatalf("unexpected tool output: %+v", parsed)
	}
}

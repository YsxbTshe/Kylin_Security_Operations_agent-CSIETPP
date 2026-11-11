package executor

import (
	"slices"
	"testing"

	daemonconfig "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/config"
)

// 只读诊断此前被无条件套上 sudo，于是「查磁盘」这类不需要任何特权的命令，
// 可用性反而绑在 sudo 能否提权上——守护进程的 systemd 加固隐式打开
// NoNewPrivileges 后 sudo 永远失败，整批只读工具随之全灭。
func TestReadOnlyDiagnosticsDoNotRequireRoot(t *testing.T) {
	service := newTestService()
	for _, testCase := range []struct {
		name  string
		build func(map[string]string) (commandSpec, string, error)
	}{
		{"disk_usage", service.buildDiskUsageCommand},
		{"memory_usage", service.buildMemoryUsageCommand},
		{"load_average", service.buildLoadAverageCommand},
		{"process_list", service.buildProcessListCommand},
		{"list_services", service.buildListServicesCommand},
		{"kernel_info", service.buildKernelInfoCommand},
	} {
		spec, _, err := testCase.build(map[string]string{})
		if err != nil {
			t.Fatalf("%s build failed: %v", testCase.name, err)
		}
		if spec.needsRoot {
			t.Fatalf("%s 不需要特权，不应标记 needsRoot", testCase.name)
		}
	}
}

// 反向断言：真正需要特权的命令必须保留标记，否则改动会静默削掉写操作的能力。
func TestPrivilegedCommandsRequireRoot(t *testing.T) {
	service := newTestService()

	restart, _, err := service.buildRestartServiceCommand(map[string]string{"service": "nginx"})
	if err != nil {
		t.Fatalf("restart build failed: %v", err)
	}
	if !restart.needsRoot {
		t.Fatal("restart_service 必须标记 needsRoot")
	}

	block, _, err := service.buildBlockIPCommand(map[string]string{"ip": "203.0.113.7", "scope": "ingress", "duration": "10m"})
	if err != nil {
		t.Fatalf("block_ip build failed: %v", err)
	}
	if !block.needsRoot {
		t.Fatal("block_ip 必须标记 needsRoot")
	}
}

// journalctl 不应标记 needsRoot：它靠 daemon unit 的 SupplementaryGroups=systemd-journal
// 拿到读全量日志的资格（ADR-002 §3.6），而不是提权。若把它标成 needsRoot，会被路由到
// 特权助手，而助手并不提供日志查询——工具就此变成死路（这个洞真实发生过一次）。
func TestJournalctlUsesGroupMembershipNotPrivilege(t *testing.T) {
	service := newTestService()
	journal, _, err := service.buildJournalctlCommand(map[string]string{"unit": "nginx"})
	if err != nil {
		t.Fatalf("journalctl build failed: %v", err)
	}
	if journal.needsRoot {
		t.Fatal("journalctl_query 不应标记 needsRoot（改用 systemd-journal 组成员身份）")
	}
}

// 工具目录声明的路径根若超出执行器底线，工具会「目录里可见、调用被拒」。
// 这个不一致只能在运行时以一句 outside allowed roots 暴露，必须能在启动时查出来。
func TestUnsupportedPathRootsDetectsCatalogDrift(t *testing.T) {
	service := NewService(daemonconfig.Config{
		AllowedPathRoots:      []string{"/var/log", "/opt"},
		MaxCommandOutputBytes: 4096,
		CommandTimeoutSeconds: 20,
	})

	if got := service.UnsupportedPathRoots([]string{"/var/log", "/var/log/aiops-demo", "/opt"}); len(got) != 0 {
		t.Fatalf("底线覆盖的根不应被报告，却得到 %v", got)
	}

	got := service.UnsupportedPathRoots([]string{"/var/log", "/tmp/aiops-demo-logs", "/srv"})
	slices.Sort(got)
	want := []string{"/srv", "/tmp/aiops-demo-logs"}
	if !slices.Equal(got, want) {
		t.Fatalf("期望报告 %v，实际 %v", want, got)
	}
}

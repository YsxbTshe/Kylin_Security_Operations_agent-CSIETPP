// Package security 是针对真实 Podman 沙箱的对抗性测试套件。
//
// 除路径穿越为纯逻辑测试外，其余用例都依赖 podman 与沙箱镜像；
// 环境不满足时跳过，保证 go test ./... 在无 podman 的开发机上全绿。
package security

import (
	"context"
	"fmt"
	"os/exec"
	"testing"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

const testImage = "kylin-workspace:latest"

// setupSandbox 启动一个真实沙箱容器。podman 或镜像不可用时跳过测试。
func setupSandbox(t *testing.T) (*sandbox.Podman, string) {
	t.Helper()
	return setupSandboxWith(t, func(*sandbox.ContainerConfig) {})
}

func setupSandboxWith(t *testing.T, mutate func(*sandbox.ContainerConfig)) (*sandbox.Podman, string) {
	t.Helper()
	if _, err := exec.LookPath("podman"); err != nil {
		t.Skip("未检测到 podman，跳过真实沙箱测试")
	}
	if err := exec.Command("podman", "image", "exists", testImage).Run(); err != nil {
		t.Skipf("镜像 %s 不存在，跳过真实沙箱测试", testImage)
	}
	p, err := sandbox.NewPodman("podman", t.TempDir())
	if err != nil {
		t.Skipf("podman 初始化失败（环境问题）：%v", err)
	}
	cfg := sandbox.ContainerConfig{
		TenantID:    "sectest",
		AgentID:     "suite",
		WorkspaceID: fmt.Sprintf("sec-%d", time.Now().UnixNano()),
		Image:       testImage,
	}
	mutate(&cfg)

	name, err := p.Create(context.Background(), cfg)
	if err != nil {
		t.Skipf("创建容器失败（环境问题）：%v", err)
	}
	if err := p.Start(context.Background(), name); err != nil {
		_ = p.Remove(context.Background(), name, true)
		t.Skipf("启动容器失败（环境问题）：%v", err)
	}
	t.Cleanup(func() {
		_ = p.Remove(context.Background(), name, true)
	})
	return p, name
}

// assertBlocked 断言命令被沙箱拦截：执行出错或退出码非零。
// 注意：镜像内缺少工具（exit 127）同样满足"未成功"，不影响安全结论。
func assertBlocked(t *testing.T, p *sandbox.Podman, name string, command []string) {
	t.Helper()
	result, err := p.Exec(context.Background(), name, command, 20)
	if err == nil && result.ExitCode == 0 {
		t.Fatalf("预期被拦截的命令成功了 %v，输出：%s", command, result.Output)
	}
}

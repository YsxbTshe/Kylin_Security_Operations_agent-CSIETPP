package security

import (
	"context"
	"testing"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

// TestForkBombBlockedByPidsLimit 验证 fork bomb 被 --pids-limit 拦截。
// 耗时测试：go test -short 时跳过。
func TestForkBombBlockedByPidsLimit(t *testing.T) {
	if testing.Short() {
		t.Skip("耗时测试，-short 模式跳过")
	}
	// 收紧 pids 上限，让 fork bomb 更快触顶。
	p, name := setupSandboxWith(t, func(cfg *sandbox.ContainerConfig) {
		cfg.PidsLimit = 64
	})

	result, err := p.Exec(context.Background(), name, []string{"bash", "-c", ":(){ :|:& };:"}, 60)
	if err == nil && result.ExitCode == 0 {
		t.Fatalf("fork bomb 未被 pids limit 拦截，exit=0 输出：%s", result.Output)
	}
}

// TestProcessLimitHolds 验证并发进程数受限时普通负载仍可完成。
func TestProcessLimitHolds(t *testing.T) {
	p, name := setupSandbox(t)
	result, err := p.Exec(context.Background(), name,
		[]string{"bash", "-c", "for i in 1 2 3; do true; done && echo done"}, 30)
	if err != nil {
		t.Fatalf("执行失败：%v", err)
	}
	if result.ExitCode != 0 {
		t.Fatalf("正常负载不应被限制，exit=%d 输出：%s", result.ExitCode, result.Output)
	}
}

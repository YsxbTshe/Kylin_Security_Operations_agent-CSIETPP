package security

import "testing"

// TestSandboxEscapeAttemptsBlocked 验证常见逃逸手段全部被拦截。
// 防线：--cap-drop=ALL、no-new-privileges、read-only、自定义 seccomp、非 root 用户。
func TestSandboxEscapeAttemptsBlocked(t *testing.T) {
	p, name := setupSandbox(t)

	cases := []struct {
		desc    string
		command []string
	}{
		{"mount 挂载新文件系统", []string{"mount", "-t", "tmpfs", "tmpfs", "/mnt"}},
		{"unshare 创建新命名空间", []string{"unshare", "-Urm", "true"}},
		{"nsenter 进入宿主命名空间", []string{"nsenter", "-t", "1", "-m", "-u", "-i", "-n", "true"}},
		{"写 /proc/sysrq-trigger", []string{"bash", "-c", "echo h > /proc/sysrq-trigger"}},
		{"写 /proc/sys 内核参数", []string{"bash", "-c", "echo 1 > /proc/sys/kernel/sysrq"}},
		{"加载内核模块", []string{"insmod", "/dev/null"}},
		{"ptrace 宿主进程", []string{"bash", "-c", "exec 100</proc/1/mem"}},
	}
	for _, tc := range cases {
		t.Run(tc.desc, func(t *testing.T) {
			assertBlocked(t, p, name, tc.command)
		})
	}
}

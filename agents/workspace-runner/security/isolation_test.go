package security

import (
	"context"
	"strings"
	"testing"
)

// TestNetworkIsolation 验证 --network=none：容器内无法建立任何对外连接。
func TestNetworkIsolation(t *testing.T) {
	p, name := setupSandbox(t)

	cases := []struct {
		desc    string
		command []string
	}{
		{"bash /dev/tcp 直连外网", []string{"bash", "-c", "echo > /dev/tcp/223.5.5.5/53"}},
		{"python3 socket 连接外网", []string{"python3", "-c", "import socket; socket.create_connection(('223.5.5.5', 53), timeout=3)"}},
		{"域名解析", []string{"bash", "-c", "echo > /dev/tcp/example.com/80"}},
		{"curl 外网", []string{"curl", "--max-time", "3", "http://223.5.5.5/"}},
		{"ping 外网", []string{"ping", "-c", "1", "-W", "3", "223.5.5.5"}},
	}
	for _, tc := range cases {
		t.Run(tc.desc, func(t *testing.T) {
			assertBlocked(t, p, name, tc.command)
		})
	}
}

// TestRootfsReadOnly 验证根文件系统只读，唯一可写区域是 /workspace tmpfs。
func TestRootfsReadOnly(t *testing.T) {
	p, name := setupSandbox(t)

	writesBlocked := []struct {
		desc    string
		command []string
	}{
		{"写 /etc", []string{"touch", "/etc/kylin-probe"}},
		{"写 /usr", []string{"bash", "-c", "echo x > /usr/kylin-probe"}},
		{"写 /var", []string{"mkdir", "/var/kylin-probe"}},
		{"写 / 根目录", []string{"bash", "-c", "echo x > /kylin-probe"}},
		{"重挂载为可写", []string{"mount", "-o", "remount,rw", "/"}},
	}
	for _, tc := range writesBlocked {
		t.Run(tc.desc, func(t *testing.T) {
			assertBlocked(t, p, name, tc.command)
		})
	}

	t.Run("/workspace 可写", func(t *testing.T) {
		result, err := p.Exec(context.Background(), name,
			[]string{"bash", "-c", "echo kylin-ok > /workspace/probe.txt && cat /workspace/probe.txt"}, 20)
		if err != nil {
			t.Fatalf("执行失败：%v", err)
		}
		if result.ExitCode != 0 || !strings.Contains(result.Output, "kylin-ok") {
			t.Fatalf("/workspace 应可写，exit=%d 输出：%s", result.ExitCode, result.Output)
		}
	})
}

// TestNoHostSecretsVisible 验证容器内看不到宿主的 socket、环境变量与密钥目录。
func TestNoHostSecretsVisible(t *testing.T) {
	p, name := setupSandbox(t)
	ctx := context.Background()

	t.Run("环境变量白名单", func(t *testing.T) {
		result, err := p.Exec(ctx, name, []string{"env"}, 20)
		if err != nil || result.ExitCode != 0 {
			t.Fatalf("env 执行失败：%v exit=%d", err, result.ExitCode)
		}
		allowed := []string{"HOME=", "PATH=", "LANG="}
		for _, line := range strings.Split(result.Output, "\n") {
			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}
			ok := false
			for _, prefix := range allowed {
				if strings.HasPrefix(line, prefix) {
					ok = true
					break
				}
			}
			if !ok {
				t.Fatalf("容器内出现白名单外的环境变量：%s", line)
			}
			for _, marker := range []string{"KYLIN_", "TOKEN", "SECRET", "PASSWORD", "PRIVATE"} {
				if strings.Contains(strings.ToUpper(line), marker) {
					t.Fatalf("环境变量疑似泄露宿主敏感信息：%s", line)
				}
			}
		}
	})

	t.Run("PID1 environ 无宿主变量", func(t *testing.T) {
		result, err := p.Exec(ctx, name, []string{"bash", "-c", `tr '\0' '\n' < /proc/1/environ`}, 20)
		if err != nil || result.ExitCode != 0 {
			t.Fatalf("读取 environ 失败：%v exit=%d", err, result.ExitCode)
		}
		if strings.Contains(result.Output, "KYLIN_") {
			t.Fatalf("PID1 environ 含 KYLIN_ 前缀变量：%s", result.Output)
		}
	})

	t.Run("挂载面不含宿主 socket", func(t *testing.T) {
		result, err := p.Exec(ctx, name, []string{"cat", "/proc/mounts"}, 20)
		if err != nil || result.ExitCode != 0 {
			t.Fatalf("读取 mounts 失败：%v exit=%d", err, result.ExitCode)
		}
		for _, marker := range []string{"workspace.sock", "/run/kylin-workspace", ".ssh"} {
			if strings.Contains(result.Output, marker) {
				t.Fatalf("挂载面暴露宿主路径 %s：%s", marker, result.Output)
			}
		}
	})

	t.Run("容器内无 runner socket 文件", func(t *testing.T) {
		result, err := p.Exec(ctx, name, []string{"find", "/run", "-name", "*.sock"}, 20)
		if err != nil {
			t.Fatalf("find 执行失败：%v", err)
		}
		if strings.TrimSpace(result.Output) != "" {
			t.Fatalf("容器 /run 内发现 socket 文件：%s", result.Output)
		}
	})

	t.Run("宿主密钥目录不可见", func(t *testing.T) {
		// 容器没有挂载任何宿主目录，这些路径要么不存在要么无权限。
		assertBlocked(t, p, name, []string{"cat", "/root/.ssh/id_rsa"})
		assertBlocked(t, p, name, []string{"ls", "/host"})
	})
}

// Package sandbox 封装 rootless Podman 容器的生命周期管理。
//
// 安全边界由以下约束组成：
//   - --read-only：容器根文件系统只读
//   - --security-opt=no-new-privileges：禁止提权
//   - --cap-drop=ALL：丢弃所有 Linux capabilities
//   - --network=none：无网络接口
//   - --pids-limit=256：限制进程数
//   - --memory=512m：内存上限
//   - --tmpfs=/workspace:rw,size=256m：唯一可写区域是 tmpfs
//   - 自定义 seccomp profile：禁止 mount/ptrace/clone with new namespace
//   - 无环境变量注入：容器内没有宿主敏感变量
//   - 无 volume 挂载：容器与宿主文件系统完全隔离
package sandbox

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// ContainerState 描述容器的当前状态。
type ContainerState string

const (
	StateRunning ContainerState = "running"
	StateStopped ContainerState = "stopped"
	StateUnknown ContainerState = "unknown"
	StateNone    ContainerState = "none"
)

// ContainerConfig 描述创建容器所需的参数。
type ContainerConfig struct {
	TenantID    string
	AgentID     string
	WorkspaceID string
	Image       string
	MemoryMB    int
	PidsLimit   int
	TmpfsSizeMB int
	TimeoutSec  int
}

// ContainerName 返回容器的唯一名称。格式：kylin-ws-{tenant}-{agent}-{short_id}。
func (c ContainerConfig) ContainerName() string {
	short := c.WorkspaceID
	if len(short) > 12 {
		short = short[:12]
	}
	return fmt.Sprintf("%s-%s-%s-%s", containerNamePrefix, sanitize(c.TenantID), sanitize(c.AgentID), short)
}

// Podman 封装 Podman CLI 调用。所有操作通过 exec.Command 驱动，不引入 Go binding。
type Podman struct {
	binary      string
	seccompPath string
	mu          sync.Mutex
}

// NewPodman 创建 Podman 封装。seccompProfileDir 是 seccomp profile JSON 文件的写入目录。
func NewPodman(binary string, seccompProfileDir string) (*Podman, error) {
	if binary == "" {
		binary = "podman"
	}
	if _, err := exec.LookPath(binary); err != nil {
		return nil, fmt.Errorf("podman 不可达：%w（请确认已安装 rootless Podman）", err)
	}

	profilePath := filepath.Join(seccompProfileDir, "kylin-workspace-seccomp.json")
	if err := os.MkdirAll(seccompProfileDir, 0700); err != nil {
		return nil, fmt.Errorf("创建 seccomp 目录失败：%w", err)
	}
	if err := os.WriteFile(profilePath, []byte(SeccompProfile()), 0600); err != nil {
		return nil, fmt.Errorf("写入 seccomp profile 失败：%w", err)
	}

	return &Podman{binary: binary, seccompPath: profilePath}, nil
}

// Create 创建容器但不启动。返回容器名称。
func (p *Podman) Create(ctx context.Context, cfg ContainerConfig) (string, error) {
	name := cfg.ContainerName()

	memoryMB := cfg.MemoryMB
	if memoryMB <= 0 {
		memoryMB = DefaultMemoryLimitMB
	}
	pidsLimit := cfg.PidsLimit
	if pidsLimit <= 0 {
		pidsLimit = DefaultPidsLimit
	}
	tmpfsSizeMB := cfg.TmpfsSizeMB
	if tmpfsSizeMB <= 0 {
		tmpfsSizeMB = DefaultTmpfsSizeMB
	}
	image := cfg.Image
	if image == "" {
		image = "kylin-workspace:latest"
	}

	args := []string{
		"create",
		"--name", name,
		"--read-only",
		"--security-opt=no-new-privileges",
		"--security-opt=seccomp=" + p.seccompPath,
		"--cap-drop=ALL",
		"--network=none",
		"--pids-limit", fmt.Sprintf("%d", pidsLimit),
		"--memory", fmt.Sprintf("%dm", memoryMB),
		"--tmpfs", fmt.Sprintf("%s:rw,size=%dm,noexec,nodev,nosuid", workspaceMountPoint, tmpfsSizeMB),
		"--workdir", workspaceMountPoint,
		"--env=HOME=/workspace",
		"--env=PATH=/usr/local/bin:/usr/bin:/bin",
		"--env=LANG=C.UTF-8",
		image,
		"sleep", "infinity",
	}

	if _, err := p.run(ctx, args...); err != nil {
		return "", fmt.Errorf("创建容器失败：%w", err)
	}
	return name, nil
}

// Start 启动已创建的容器。
func (p *Podman) Start(ctx context.Context, name string) error {
	if _, err := p.run(ctx, "start", name); err != nil {
		return fmt.Errorf("启动容器失败：%w", err)
	}
	return nil
}

// Stop 停止容器。
func (p *Podman) Stop(ctx context.Context, name string) error {
	if _, err := p.run(ctx, "stop", "--time=5", name); err != nil {
		return fmt.Errorf("停止容器失败：%w", err)
	}
	return nil
}

// Remove 删除容器。force=true 时先停止再删除。
func (p *Podman) Remove(ctx context.Context, name string, force bool) error {
	args := []string{"rm"}
	if force {
		args = append(args, "--force")
	}
	args = append(args, name)
	if _, err := p.run(ctx, args...); err != nil {
		return fmt.Errorf("删除容器失败：%w", err)
	}
	return nil
}

// ExecResult 是容器内命令执行的结果。
type ExecResult struct {
	Output   string
	ExitCode int
}

// Exec 在运行中的容器内执行命令。
func (p *Podman) Exec(ctx context.Context, name string, command []string, timeoutSec int) (ExecResult, error) {
	if timeoutSec <= 0 {
		timeoutSec = DefaultTimeoutSec
	}
	if len(command) == 0 {
		return ExecResult{}, fmt.Errorf("命令不能为空")
	}

	execCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutSec)*time.Second)
	defer cancel()

	args := []string{"exec", name}
	args = append(args, command...)
	cmd := exec.CommandContext(execCtx, p.binary, args...)

	output, err := cmd.CombinedOutput()
	text := truncateOutput(string(output), DefaultMaxOutputBytes)

	exitCode := 0
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			exitCode = exitErr.ExitCode()
		} else {
			return ExecResult{Output: text, ExitCode: -1}, fmt.Errorf("执行命令失败：%w", err)
		}
	}

	return ExecResult{Output: text, ExitCode: exitCode}, nil
}

// CopyIn 将内容写入容器内指定路径。通过 Exec + tee 实现，避免 podman cp 的 volume 需求。
func (p *Podman) CopyIn(ctx context.Context, name, path string, content []byte) error {
	if !isValidWorkspacePath(path) {
		return fmt.Errorf("路径不合法：%s", path)
	}
	dir := filepath.Dir(path)
	if dir != "." && dir != "/" {
		if _, err := p.Exec(ctx, name, []string{"mkdir", "-p", dir}, 10); err != nil {
			return fmt.Errorf("创建目录失败：%w", err)
		}
	}
	execCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(execCtx, p.binary, "exec", "-i", name, "tee", path)
	cmd.Stdin = strings.NewReader(string(content))
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("写入文件失败：%s：%w", strings.TrimSpace(string(output)), err)
	}
	return nil
}

// ReadFile 读取容器内指定路径的内容。
func (p *Podman) ReadFile(ctx context.Context, name, path string) (string, error) {
	if !isValidWorkspacePath(path) {
		return "", fmt.Errorf("路径不合法：%s", path)
	}
	result, err := p.Exec(ctx, name, []string{"cat", path}, 30)
	if err != nil {
		return "", err
	}
	if result.ExitCode != 0 {
		return "", fmt.Errorf("读取文件失败（exit=%d）：%s", result.ExitCode, result.Output)
	}
	return result.Output, nil
}

// ImageInfo 描述一个本地镜像。
type ImageInfo struct {
	Name      string
	CreatedAt time.Time
}

// Commit 将容器固化为镜像（COW 快照）。
func (p *Podman) Commit(ctx context.Context, container, image string) error {
	if _, err := p.run(ctx, "commit", "--quiet", container, image); err != nil {
		return fmt.Errorf("提交镜像失败：%w", err)
	}
	return nil
}

// ListImages 列出本地镜像。时间解析失败时 CreatedAt 为零值。
func (p *Podman) ListImages(ctx context.Context) ([]ImageInfo, error) {
	output, err := p.run(ctx, "images", "--format", "{{.Repository}}:{{.Tag}}\t{{.CreatedAt}}")
	if err != nil {
		return nil, fmt.Errorf("列出镜像失败：%w", err)
	}
	var images []ImageInfo
	for _, line := range strings.Split(output, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		name, created, _ := strings.Cut(line, "\t")
		info := ImageInfo{Name: name}
		if parsed, err := time.Parse("2006-01-02 15:04:05.999999999 -0700 MST", strings.TrimSpace(created)); err == nil {
			info.CreatedAt = parsed
		}
		images = append(images, info)
	}
	return images, nil
}

// RemoveImage 删除镜像。
func (p *Podman) RemoveImage(ctx context.Context, name string, force bool) error {
	args := []string{"rmi"}
	if force {
		args = append(args, "--force")
	}
	args = append(args, name)
	if _, err := p.run(ctx, args...); err != nil {
		return fmt.Errorf("删除镜像失败：%w", err)
	}
	return nil
}

// Inspect 查询容器状态。
func (p *Podman) Inspect(ctx context.Context, name string) (ContainerState, error) {
	output, err := p.run(ctx, "inspect", "--format", "{{.State.Status}}", name)
	if err != nil {
		return StateNone, nil
	}
	switch strings.TrimSpace(output) {
	case "running":
		return StateRunning, nil
	case "exited", "stopped":
		return StateStopped, nil
	default:
		return StateUnknown, nil
	}
}

// ListContainers 列出所有 kylin-ws 前缀的容器。
func (p *Podman) ListContainers(ctx context.Context) ([]string, error) {
	output, err := p.run(ctx, "ps", "-a", "--filter", "name="+containerNamePrefix, "--format", "{{.Names}}")
	if err != nil {
		return nil, err
	}
	var names []string
	for _, line := range strings.Split(output, "\n") {
		if trimmed := strings.TrimSpace(line); trimmed != "" {
			names = append(names, trimmed)
		}
	}
	return names, nil
}

// run 执行 podman 命令并返回 stdout。
func (p *Podman) run(ctx context.Context, args ...string) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	cmd := exec.CommandContext(ctx, p.binary, args...)
	cmd.Env = []string{"HOME=" + os.Getenv("HOME"), "PATH=" + os.Getenv("PATH")}

	output, err := cmd.CombinedOutput()
	text := strings.TrimSpace(string(output))
	if err != nil {
		if text == "" {
			text = err.Error()
		}
		return text, fmt.Errorf("%s", text)
	}
	return text, nil
}

// SanitizeSegment 将字符串转为容器/镜像名称安全片段。
func SanitizeSegment(value string) string {
	return sanitize(value)
}

// sanitize 将字符串转为容器名称安全片段。
func sanitize(value string) string {
	var b strings.Builder
	for _, r := range value {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' {
			b.WriteRune(r)
		} else if r >= 'A' && r <= 'Z' {
			b.WriteRune(r + 32)
		} else {
			b.WriteRune('-')
		}
	}
	result := b.String()
	if len(result) > 32 {
		result = result[:32]
	}
	return result
}

// IsValidPath 校验路径是否在 workspace 内，防止路径穿越。
func IsValidPath(path string) bool {
	return isValidWorkspacePath(path)
}

// isValidWorkspacePath 校验路径是否在 workspace 内。
func isValidWorkspacePath(path string) bool {
	if strings.Contains(path, "\x00") {
		return false
	}
	cleaned := filepath.Clean(path)
	if strings.HasPrefix(cleaned, "..") || filepath.IsAbs(cleaned) {
		return false
	}
	depth := strings.Count(cleaned, string(filepath.Separator)) + 1
	if depth > DefaultMaxPathDepth {
		return false
	}
	for _, segment := range strings.Split(cleaned, string(filepath.Separator)) {
		if len(segment) > DefaultMaxFileNameLen {
			return false
		}
	}
	return true
}

// truncateOutput 截断超长输出。
func truncateOutput(text string, maxBytes int) string {
	if len(text) <= maxBytes {
		return text
	}
	return text[:maxBytes] + "\n... [truncated]"
}

// ExecJSON 在容器内执行命令并将 stdout 解析为 JSON。
func (p *Podman) ExecJSON(ctx context.Context, name string, command []string, timeoutSec int, target any) error {
	result, err := p.Exec(ctx, name, command, timeoutSec)
	if err != nil {
		return err
	}
	if result.ExitCode != 0 {
		return fmt.Errorf("命令退出码 %d：%s", result.ExitCode, result.Output)
	}
	return json.Unmarshal([]byte(result.Output), target)
}

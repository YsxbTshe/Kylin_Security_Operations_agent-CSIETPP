// Package workspace 封装 Node Executor 与 Workspace Runner 之间的 Unix Socket 通信。
//
// Workspace Runner 在 Podman 沙箱内执行文件操作和命令。安全边界由容器保证
// （无网络、无宿主访问、seccomp 限制），本客户端不做额外的密码学校验——
// 控制面的 DispatchGate 在 gRPC 入口已经完成了授权。
package workspace

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"time"
)

// DefaultSocketPath 是 Workspace Runner 监听的 Unix Socket。
const DefaultSocketPath = "/run/kylin-workspace/workspace.sock"

// maxResponseBytes 限制单条响应体积（2MB）。
const maxResponseBytes = 2 << 20

// Request 是 Node Executor → Workspace Runner 的请求。
type Request struct {
	RequestID   string         `json:"requestId"`
	TenantID    string         `json:"tenantId"`
	AgentID     string         `json:"agentId"`
	WorkspaceID string         `json:"workspaceId"`
	Action      string         `json:"action"`
	Arguments   map[string]any `json:"arguments,omitempty"`
	TimeoutSec  int            `json:"timeoutSec,omitempty"`
}

// Response 是 Workspace Runner → Node Executor 的应答。
type Response struct {
	Output   string `json:"output,omitempty"`
	Error    string `json:"error,omitempty"`
	ExitCode int    `json:"exitCode,omitempty"`
}

// Client 是 Workspace Runner 的 Unix Socket 客户端。
type Client struct {
	socketPath string
	timeout    time.Duration
}

// NewClient 创建客户端。socketPath 为空时使用默认路径。
func NewClient(socketPath string, timeout time.Duration) Client {
	if strings.TrimSpace(socketPath) == "" {
		socketPath = DefaultSocketPath
	}
	if timeout <= 0 {
		timeout = 5 * time.Minute
	}
	return Client{socketPath: socketPath, timeout: timeout}
}

// Call 发送一次请求。每条请求一条连接、一来一回、无状态。
func (c Client) Call(req Request) (Response, error) {
	deadline := time.Now().Add(c.timeout)
	conn, err := net.DialTimeout("unix", c.socketPath, 10*time.Second)
	if err != nil {
		return Response{}, fmt.Errorf("workspace runner 不可达（%s）：%w", c.socketPath, err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(deadline)

	payload, err := json.Marshal(req)
	if err != nil {
		return Response{}, fmt.Errorf("编码 workspace 请求失败：%w", err)
	}
	if _, err := conn.Write(append(payload, '\n')); err != nil {
		return Response{}, fmt.Errorf("发送 workspace 请求失败：%w", err)
	}
	if unixConn, ok := conn.(*net.UnixConn); ok {
		_ = unixConn.CloseWrite()
	}

	raw, err := io.ReadAll(io.LimitReader(conn, maxResponseBytes))
	if err != nil {
		return Response{}, fmt.Errorf("读取 workspace 应答失败：%w", err)
	}
	var resp Response
	if err := json.Unmarshal(raw, &resp); err != nil {
		return Response{}, fmt.Errorf("解析 workspace 应答失败：%w", err)
	}
	if strings.TrimSpace(resp.Error) != "" {
		return resp, fmt.Errorf("%s", resp.Error)
	}
	return resp, nil
}

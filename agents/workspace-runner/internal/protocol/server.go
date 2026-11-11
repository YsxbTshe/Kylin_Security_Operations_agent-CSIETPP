package protocol

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

// Handler 处理具体操作。由上层注入，server 只做协议解析和分发。
type Handler interface {
	Handle(ctx context.Context, req Request) Response
}

// HandlerFunc 是 Handler 的函数适配器。
type HandlerFunc func(ctx context.Context, req Request) Response

func (f HandlerFunc) Handle(ctx context.Context, req Request) Response { return f(ctx, req) }

// Server 是 Workspace Runner 的 Unix Socket 服务端。
type Server struct {
	handler        Handler
	socketPath     string
	allowedPeerUID *uint32
}

type ServerOptions struct {
	Handler    Handler
	SocketPath string
	// AllowedPeerUID 非 nil 时启用 SO_PEERCRED 对端校验：只有该 uid 的进程能下发请求。
	// nil 表示不校验——只有本地开发与单测该走这条路径，节点上由 systemd unit
	// 通过 KYLIN_WORKSPACE_PEER_USER 强制配上（见 cmd/workspace-runner/main.go）。
	AllowedPeerUID *uint32
}

func NewServer(opts ServerOptions) *Server {
	socketPath := opts.SocketPath
	if socketPath == "" {
		socketPath = DefaultSocketPath
	}
	return &Server{handler: opts.Handler, socketPath: socketPath, allowedPeerUID: opts.AllowedPeerUID}
}

// Serve 监听 Unix Socket 并处理请求，直到 ctx 取消。
func (s *Server) Serve(ctx context.Context) error {
	dir := filepath.Dir(s.socketPath)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return fmt.Errorf("创建 socket 目录失败：%w", err)
	}
	_ = os.Remove(s.socketPath)

	listener, err := net.Listen("unix", s.socketPath)
	if err != nil {
		return fmt.Errorf("监听 socket 失败：%w", err)
	}
	defer listener.Close()
	defer os.Remove(s.socketPath)

	// Go 建出来的 socket 受 umask 影响，通常是 0755——属组没有写位就 connect 不上。
	// 运行 runner 的用户与调用方（node-executor）是两个账号，靠的是 systemd 把
	// RuntimeDirectory 的属组设成调用方的组，所以这里必须显式放到 0660：
	// 属主 + 属组可连，其他用户一律连不上。
	if err := os.Chmod(s.socketPath, 0660); err != nil {
		return fmt.Errorf("设置 socket 权限失败：%w", err)
	}

	go func() {
		<-ctx.Done()
		_ = listener.Close()
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go s.serveConn(ctx, conn)
	}
}

func (s *Server) serveConn(ctx context.Context, conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(60 * time.Second))

	if !s.authorizePeer(conn) {
		return
	}

	var req Request
	decoder := json.NewDecoder(&limitedReader{conn: conn, remaining: maxMessageBytes})
	if err := decoder.Decode(&req); err != nil {
		s.reply(conn, Response{Error: "请求解析失败：" + err.Error()})
		return
	}

	if !IsValidAction(req.Action) {
		s.reply(conn, Response{Error: "未知操作：" + req.Action})
		return
	}

	s.reply(conn, s.handler.Handle(ctx, req))
}

// authorizePeer 校验对端身份。未配置 AllowedPeerUID 时直接放行；配置了就必须能读到
// 对端 uid 且完全相等——读不到（比如非 Linux 平台）按拒绝处理，不给"降级放行"的口子。
func (s *Server) authorizePeer(conn net.Conn) bool {
	if s.allowedPeerUID == nil {
		return true
	}
	uid, err := peerUID(conn)
	if err != nil {
		log.Printf("拒绝连接：无法确认对端身份：%v", err)
		s.reply(conn, Response{Error: "无法确认对端身份"})
		return false
	}
	if uid != *s.allowedPeerUID {
		// 不回显期望值，避免把部署配置泄露给非预期调用方。
		log.Printf("拒绝连接：对端 uid=%d 不是授权调用方", uid)
		s.reply(conn, Response{Error: "调用方未授权"})
		return false
	}
	return true
}

func (s *Server) reply(conn net.Conn, resp Response) {
	payload, err := json.Marshal(resp)
	if err != nil {
		payload = []byte(`{"error":"应答编码失败"}`)
	}
	_, _ = conn.Write(payload)
}

// limitedReader 限制单条请求体积。
type limitedReader struct {
	conn      net.Conn
	remaining int64
}

func (r *limitedReader) Read(buf []byte) (int, error) {
	if r.remaining <= 0 {
		return 0, fmt.Errorf("请求超过大小上限")
	}
	if int64(len(buf)) > r.remaining {
		buf = buf[:r.remaining]
	}
	n, err := r.conn.Read(buf)
	r.remaining -= int64(n)
	return n, err
}

// PodmanHandler 是 Handler 的默认实现，将操作路由到 Podman 沙箱。
type PodmanHandler struct {
	podman *sandbox.Podman
}

func NewPodmanHandler(podman *sandbox.Podman) *PodmanHandler {
	return &PodmanHandler{podman: podman}
}

func (h *PodmanHandler) Handle(ctx context.Context, req Request) Response {
	// 容器名由 tenant/agent/workspace 三元组派生（ContainerName），任一为空都会退化成
	// 共享容器名，让多个实例共用同一个沙箱——隔离键缺失必须拒绝，不能兜底。
	if strings.TrimSpace(req.TenantID) == "" || strings.TrimSpace(req.AgentID) == "" || strings.TrimSpace(req.WorkspaceID) == "" {
		return Response{Error: "缺少隔离键（tenantId/agentId/workspaceId 不能为空），拒绝执行以避免多实例共享沙箱"}
	}
	containerName := containerNameFromRequest(req)

	switch req.Action {
	case ActionExec:
		return h.handleExec(ctx, containerName, req)
	case ActionReadFile:
		return h.handleReadFile(ctx, containerName, req)
	case ActionWriteFile:
		return h.handleWriteFile(ctx, containerName, req)
	case ActionGlob:
		return h.handleGlob(ctx, containerName, req)
	case ActionListDir:
		return h.handleListDir(ctx, containerName, req)
	default:
		return Response{Error: "操作未实现：" + req.Action}
	}
}

func (h *PodmanHandler) handleExec(ctx context.Context, name string, req Request) Response {
	command, ok := stringSliceArg(req.Arguments, "command")
	if !ok || len(command) == 0 {
		return Response{Error: "缺少 command 参数"}
	}
	timeout := req.TimeoutSec
	if timeout <= 0 {
		timeout = sandbox.DefaultTimeoutSec
	}
	result, err := h.podman.Exec(ctx, name, command, timeout)
	if err != nil {
		return Response{Output: result.Output, Error: err.Error(), ExitCode: result.ExitCode}
	}
	return Response{Output: result.Output, ExitCode: result.ExitCode}
}

func (h *PodmanHandler) handleReadFile(ctx context.Context, name string, req Request) Response {
	path, _ := stringArg(req.Arguments, "path")
	if path == "" {
		return Response{Error: "缺少 path 参数"}
	}
	content, err := h.podman.ReadFile(ctx, name, path)
	if err != nil {
		return Response{Error: err.Error()}
	}
	return Response{Output: content}
}

func (h *PodmanHandler) handleWriteFile(ctx context.Context, name string, req Request) Response {
	path, _ := stringArg(req.Arguments, "path")
	content, _ := stringArg(req.Arguments, "content")
	if path == "" {
		return Response{Error: "缺少 path 参数"}
	}
	if err := h.podman.CopyIn(ctx, name, path, []byte(content)); err != nil {
		return Response{Error: err.Error()}
	}
	return Response{Output: "ok"}
}

func (h *PodmanHandler) handleGlob(ctx context.Context, name string, req Request) Response {
	pattern, _ := stringArg(req.Arguments, "pattern")
	if pattern == "" {
		return Response{Error: "缺少 pattern 参数"}
	}
	cleaned := sanitizeGlob(pattern)
	// 直接用 find 的 -path 参数，不经 shell 解释，避免注入。
	result, err := h.podman.Exec(ctx, name, []string{"find", "/workspace", "-path", "/workspace/" + cleaned}, 30)
	if err != nil {
		return Response{Error: err.Error()}
	}
	return Response{Output: result.Output}
}

func (h *PodmanHandler) handleListDir(ctx context.Context, name string, req Request) Response {
	path, _ := stringArg(req.Arguments, "path")
	if path == "" {
		path = "."
	}
	if !sandbox.IsValidPath(path) {
		return Response{Error: "路径不合法：" + path}
	}
	result, err := h.podman.Exec(ctx, name, []string{"ls", "-la", "/workspace/" + path}, 30)
	if err != nil {
		return Response{Error: err.Error()}
	}
	return Response{Output: result.Output}
}

func containerNameFromRequest(req Request) string {
	cfg := sandbox.ContainerConfig{
		TenantID:    req.TenantID,
		AgentID:     req.AgentID,
		WorkspaceID: req.WorkspaceID,
	}
	return cfg.ContainerName()
}

func stringArg(args map[string]any, key string) (string, bool) {
	if args == nil {
		return "", false
	}
	v, ok := args[key]
	if !ok {
		return "", false
	}
	s, ok := v.(string)
	return s, ok
}

func stringSliceArg(args map[string]any, key string) ([]string, bool) {
	if args == nil {
		return nil, false
	}
	v, ok := args[key]
	if !ok {
		return nil, false
	}
	arr, ok := v.([]any)
	if !ok {
		return nil, false
	}
	result := make([]string, 0, len(arr))
	for _, item := range arr {
		if s, ok := item.(string); ok {
			result = append(result, s)
		}
	}
	return result, len(result) > 0
}

// sanitizeGlob 对 glob pattern 做基本清理，防止 shell 注入。
// 只保留字母数字、*、?、[、]、/、.、-、_。
func sanitizeGlob(pattern string) string {
	var b []byte
	for i := 0; i < len(pattern); i++ {
		c := pattern[i]
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9':
			b = append(b, c)
		case c == '*' || c == '?' || c == '[' || c == ']' || c == '/' || c == '.' || c == '-' || c == '_':
			b = append(b, c)
		}
	}
	return string(b)
}

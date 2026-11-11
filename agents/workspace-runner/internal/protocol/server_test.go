package protocol

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type stubHandler struct {
	last Request
	resp Response
}

func (h *stubHandler) Handle(_ context.Context, req Request) Response {
	h.last = req
	return h.resp
}

func TestServerRejectsUnknownAction(t *testing.T) {
	dir := t.TempDir()
	sock := filepath.Join(dir, "test.sock")

	handler := &stubHandler{resp: Response{Output: "should-not-reach"}}
	server := NewServer(ServerOptions{Handler: handler, SocketPath: sock})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() { _ = server.Serve(ctx) }()

	// 等待 socket 就绪。
	waitForSocket(t, sock)

	conn, err := net.Dial("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	req := Request{Action: "rm_rf", TenantID: "t", AgentID: "a", WorkspaceID: "w"}
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		t.Fatal(err)
	}
	if uc, ok := conn.(*net.UnixConn); ok {
		_ = uc.CloseWrite()
	}

	var resp Response
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if resp.Error == "" {
		t.Fatal("期望拒绝未知操作")
	}
	if handler.last.Action != "" {
		t.Fatal("未知操作不应到达 handler")
	}
}

func TestServerRoutesToHandler(t *testing.T) {
	dir := t.TempDir()
	sock := filepath.Join(dir, "test.sock")

	handler := &stubHandler{resp: Response{Output: "hello"}}
	server := NewServer(ServerOptions{Handler: handler, SocketPath: sock})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() { _ = server.Serve(ctx) }()
	waitForSocket(t, sock)

	conn, err := net.Dial("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	req := Request{
		Action:      ActionReadFile,
		TenantID:    "tenant-1",
		AgentID:     "agent-1",
		WorkspaceID: "ws-1",
		Arguments:   map[string]any{"path": "test.txt"},
	}
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		t.Fatal(err)
	}
	if uc, ok := conn.(*net.UnixConn); ok {
		_ = uc.CloseWrite()
	}

	var resp Response
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if resp.Output != "hello" {
		t.Fatalf("期望 output=hello，得到 %q", resp.Output)
	}
	if handler.last.Action != ActionReadFile {
		t.Fatalf("期望 action=read_file，得到 %q", handler.last.Action)
	}
}

// 隔离键（tenant/agent/workspace）任一为空时，容器名会退化成共享名称，
// 必须直接拒绝，不能兜底执行。校验发生在触达 Podman 之前，nil 客户端是安全的。
func TestPodmanHandlerRejectsEmptyIsolationKeys(t *testing.T) {
	handler := NewPodmanHandler(nil)
	cases := []Request{
		{Action: ActionExec, TenantID: "", AgentID: "a", WorkspaceID: "w"},
		{Action: ActionExec, TenantID: "t", AgentID: "", WorkspaceID: "w"},
		{Action: ActionExec, TenantID: "t", AgentID: "a", WorkspaceID: ""},
		{Action: ActionExec, TenantID: "t", AgentID: "a", WorkspaceID: "   "},
	}
	for _, req := range cases {
		resp := handler.Handle(context.Background(), req)
		if resp.Error == "" {
			t.Fatalf("隔离键缺失必须拒绝执行：%+v", req)
		}
	}
}

func TestIsValidAction(t *testing.T) {
	cases := []struct {
		action string
		valid  bool
	}{
		{ActionExec, true},
		{ActionReadFile, true},
		{ActionWriteFile, true},
		{ActionGlob, true},
		{"rm_rf", false},
		{"exec", true},
		{"", false},
		{"EXEC", false},
	}
	for _, tc := range cases {
		if got := IsValidAction(tc.action); got != tc.valid {
			t.Errorf("IsValidAction(%q) = %v, want %v", tc.action, got, tc.valid)
		}
	}
}

func TestSanitizeGlob(t *testing.T) {
	cases := []struct {
		input    string
		expected string
	}{
		{"*.py", "*.py"},
		{"src/**/*.go", "src/**/*.go"},
		{"file;rm -rf /", "filerm-rf/"},
		{"$(whoami)", "whoami"},
		{"normal-file_name.txt", "normal-file_name.txt"},
	}
	for _, tc := range cases {
		if got := sanitizeGlob(tc.input); got != tc.expected {
			t.Errorf("sanitizeGlob(%q) = %q, want %q", tc.input, got, tc.expected)
		}
	}
}

func waitForSocket(t *testing.T, path string) {
	t.Helper()
	for i := 0; i < 100; i++ {
		if _, err := os.Stat(path); err == nil {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("socket 未就绪")
}

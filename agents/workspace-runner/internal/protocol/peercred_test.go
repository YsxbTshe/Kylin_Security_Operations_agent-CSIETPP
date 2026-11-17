package protocol

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"testing"
)

// 未配置 AllowedPeerUID 时不做对端校验——已由 TestServerRoutesToHandler 覆盖，
// 这里只覆盖「配置了」的两种结局。

func TestServerRejectsUnauthorizedPeer(t *testing.T) {
	// 取一个不可能属于任何真实进程的 uid。Linux 上 SO_PEERCRED 会读到测试进程
	// 自己的 uid 并因不相等而拒绝；非 Linux 上读不到对端凭据，同样拒绝。
	// 两条路径都必须拒绝，这正是本用例要钉住的性质。
	denied := uint32(1<<32 - 1)
	handler := &stubHandler{resp: Response{Output: "should-not-reach"}}
	resp := roundTripWithPeerUID(t, handler, &denied)

	if resp.Error == "" {
		t.Fatal("配置了 AllowedPeerUID 时，非授权对端必须被拒绝")
	}
	if handler.last.Action != "" {
		t.Fatalf("被拒绝的连接不应到达 handler，实际收到 action=%q", handler.last.Action)
	}
}

func TestServerSocketIsNotWorldAccessible(t *testing.T) {
	sock := startServer(t, &stubHandler{resp: Response{Output: "ok"}}, nil)
	info, err := os.Stat(sock)
	if err != nil {
		t.Fatal(err)
	}
	// 0660：属主与属组可连（systemd 把 RuntimeDirectory 属组设成调用方的组），
	// 其他用户没有写位就 connect 不上。
	if perm := info.Mode().Perm(); perm != 0660 {
		t.Fatalf("socket 权限应为 0660，实际 %#o", perm)
	}
}

// roundTripWithPeerUID 起一个带对端校验的 server，发一条合法请求并返回应答。
func roundTripWithPeerUID(t *testing.T, handler Handler, allowed *uint32) Response {
	t.Helper()
	sock := startServer(t, handler, allowed)

	conn, err := net.Dial("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	req := Request{Action: ActionExec, TenantID: "t", AgentID: "a", WorkspaceID: "w"}
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
	return resp
}

func startServer(t *testing.T, handler Handler, allowed *uint32) string {
	t.Helper()
	// 不用 t.TempDir()：它把测试名拼进路径，而 unix socket 路径在 macOS 上只有
	// 104 字节上限，本文件里的长测试名会直接把 bind 撑爆。
	dir, err := os.MkdirTemp("", "wsrun")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	sock := filepath.Join(dir, "test.sock")
	server := NewServer(ServerOptions{Handler: handler, SocketPath: sock, AllowedPeerUID: allowed})

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() { _ = server.Serve(ctx) }()
	waitForSocket(t, sock)
	return sock
}

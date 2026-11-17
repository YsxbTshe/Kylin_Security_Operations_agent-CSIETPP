//go:build linux

package protocol

import (
	"os"
	"testing"
)

// 只有 Linux 才有 SO_PEERCRED，因此"授权对端可以通过"这条正向用例只能在 Linux 上跑。
// 非 Linux 平台上 peerUID 永远返回错误，正向路径不存在（见 peercred_other.go）。
func TestServerAcceptsAuthorizedPeer(t *testing.T) {
	self := uint32(os.Getuid())
	handler := &stubHandler{resp: Response{Output: "hello"}}
	resp := roundTripWithPeerUID(t, handler, &self)

	if resp.Error != "" {
		t.Fatalf("授权对端不应被拒绝：%s", resp.Error)
	}
	if resp.Output != "hello" {
		t.Fatalf("期望请求到达 handler，实际应答 %+v", resp)
	}
	if handler.last.Action != ActionExec {
		t.Fatalf("handler 未收到请求，实际 action=%q", handler.last.Action)
	}
}

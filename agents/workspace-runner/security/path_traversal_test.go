package security

import (
	"context"
	"testing"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/protocol"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

// TestPathTraversalRejectedAtHandler 是纯逻辑测试，不需要 podman。
// 路径校验发生在任何 podman 调用之前，因此 nil 客户端足以证明拒绝。
func TestPathTraversalRejectedAtHandler(t *testing.T) {
	h := protocol.NewPodmanHandler(nil)

	cases := []protocol.Request{
		{Action: protocol.ActionReadFile, Arguments: map[string]any{"path": "../etc/passwd"}},
		{Action: protocol.ActionReadFile, Arguments: map[string]any{"path": "/etc/shadow"}},
		{Action: protocol.ActionReadFile, Arguments: map[string]any{"path": "a/../../etc/passwd"}},
		{Action: protocol.ActionWriteFile, Arguments: map[string]any{"path": "/etc/cron.d/evil", "content": "x"}},
		{Action: protocol.ActionWriteFile, Arguments: map[string]any{"path": "../../evil.sh", "content": "x"}},
		{Action: protocol.ActionListDir, Arguments: map[string]any{"path": "/etc"}},
		{Action: protocol.ActionListDir, Arguments: map[string]any{"path": "../../../"}},
	}
	for _, req := range cases {
		resp := h.Handle(context.Background(), req)
		if resp.Error == "" {
			t.Fatalf("预期拒绝的请求成功了：action=%s path=%v 输出=%q", req.Action, req.Arguments["path"], resp.Output)
		}
	}
}

func TestPathValidationBoundaryCases(t *testing.T) {
	blocked := []string{
		"../x", "..", ".../x", "a/../..", "/abs", "a\x00b", "./../y", "/workspace/../etc",
	}
	for _, path := range blocked {
		if sandbox.IsValidPath(path) {
			t.Errorf("IsValidPath(%q) 应拒绝", path)
		}
	}
	allowed := []string{
		"a.txt", "a/b.txt", "foo..bar/b.txt", "a/..b/c", ".",
	}
	for _, path := range allowed {
		if !sandbox.IsValidPath(path) {
			t.Errorf("IsValidPath(%q) 应放行", path)
		}
	}
}

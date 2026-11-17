package safeops

import (
	"strings"
	"testing"
)

// daemon 侧的审批门只做骨架校验；令牌真伪的权威判定在特权助手（ADR-002 §3.4）。
// 签名绑定（同一令牌换密钥/换字段后失效）由 privops 的 helper 测试覆盖，
// daemon 不持有任何签名材料。
func TestApprovalTokenGateChecksShapeOnly(t *testing.T) {
	call := SafeToolCall{
		TaskID:        "task-1",
		NodeID:        "node-1",
		ToolName:      "kylin.service.restart",
		RiskCode:      "R3",
		ArgumentsJSON: `{"service":"nginx"}`,
	}

	// 骨架正确即通过门禁（是否真令牌由助手判定）。
	call.ApprovalToken = approvalTokenForTest(call)
	if !approvalTokenHasValidShape(call) {
		t.Fatal("骨架正确的 SM2 令牌应通过 daemon 侧门禁")
	}

	// 骨架错误一律拒绝：空串、无关字符串、坏 base64、坏 protobuf、
	// 以及已退役的 HMAC 六元组形态。
	for _, bad := range []string{
		"",
		"not-a-token",
		"kylin-approval-v1.!!!not-base64",
		"kylin-approval-v1.bm90LWEtcHJvdG9idWY", // 合法 base64url 但不是 protobuf 令牌
		"approval-token-v2-0000000000000000-" + strings.Repeat("0", 64),
	} {
		call.ApprovalToken = bad
		if approvalTokenHasValidShape(call) {
			t.Fatalf("骨架非法的令牌必须被拒绝：%q", bad)
		}
	}
}

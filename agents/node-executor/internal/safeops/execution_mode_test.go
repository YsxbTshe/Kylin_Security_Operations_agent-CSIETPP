package safeops

import (
	"context"
	"strings"
	"testing"
)

// 计划模式的端侧拦截（ADR-001 §2.10.3）：即使控制面把写命令发了下来，端侧也必须拒绝。
func TestPlanModeDeniesWriteLocally(t *testing.T) {
	call := SafeToolCall{
		CommandID:     "cmd-1",
		TaskID:        "task-1",
		NodeID:        "node-1",
		TraceID:       "trace-1",
		ToolName:      "kylin.firewall.block_ip",
		ArgumentsJSON: `{"ip":"203.0.113.1","scope":"ingress","duration":"30m"}`,
		RiskCode:      "R3",
		ExecutionMode: "PLAN",
	}
	// 带上骨架合法的审批令牌与资源锁，排除"因为缺令牌才被拒"的干扰——
	// 必须证明拒绝的原因是模式本身。注意 PLAN 模式根本签不出真令牌
	// （claim 只允许 CAUTIOUS/AUTO），但这不妨碍骨架校验放行。
	call.ResourceLocks = []ResourceLock{{ResourceKey: "firewall:203.0.113.1", LockType: "WRITE_EXCLUSIVE"}}
	call.ApprovalToken = approvalTokenForTest(call)

	runner := NewRunner(runnerFakeRuntime{})
	report := runner.ExecuteReport(context.Background(), call)
	if report.Result.LocalGuardrailDecision != "DENY" {
		t.Fatalf("计划模式下的写操作必须被端侧拒绝，实际 %+v", report.Result)
	}
	if !strings.Contains(report.Result.Message, "plan mode") {
		t.Fatalf("拒绝原因应指明是计划模式，实际 %q", report.Result.Message)
	}

	// 同一条命令改为谨慎模式后不应再被模式规则拦下
	allowed := call
	allowed.ExecutionMode = "CAUTIOUS"
	allowed.ApprovalToken = approvalTokenForTest(allowed)
	report2 := runner.ExecuteReport(context.Background(), allowed)
	if strings.Contains(report2.Result.Message, "plan mode") {
		t.Fatalf("谨慎模式不应触发计划模式规则，实际 %q", report2.Result.Message)
	}
}

// 未知模式必须按最严格处理，不能因为控制面漏发字段就放宽。
func TestUnknownExecutionModeFallsBackToCautious(t *testing.T) {
	for _, mode := range []string{"", "react", "nonsense"} {
		if got := normalizeExecutionMode(mode); got != "CAUTIOUS" {
			t.Fatalf("模式 %q 应归一为 CAUTIOUS，实际 %s", mode, got)
		}
	}
}

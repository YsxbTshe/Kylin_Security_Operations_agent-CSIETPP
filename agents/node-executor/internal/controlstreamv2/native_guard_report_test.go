package controlstreamv2

import (
	"strings"
	"testing"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/hosttools"
)

// postureRuntime 满足 safeops.Runtime 且提供姿态载荷。
type postureRuntime struct {
	payload any
	calls   int
}

func (r *postureRuntime) ExecuteTool(nodeID string, toolName string, argumentsJSON string) (string, error) {
	return "{}", nil
}

func (r *postureRuntime) NativeGuardPosturePayload(nodeID string) any {
	r.calls++
	return r.payload
}

// plainRuntime 不实现姿态接口——心跳必须上报空串（控制面按 UNKNOWN fail-closed）。
type plainRuntime struct{}

func (plainRuntime) ExecuteTool(nodeID string, toolName string, argumentsJSON string) (string, error) {
	return "{}", nil
}

func TestSummarizeNativeGuardMapsOverallAndObservedAt(t *testing.T) {
	collected := time.Now().Add(-30 * time.Second).Unix()
	runtime := &postureRuntime{payload: hosttools.NativeGuardPosture{
		Overall: "critical", CollectedAtUnix: collected,
		Items: []hosttools.NativeGuardPostureItem{{Tool: "kylin.security.auditd_status", Supported: true, Status: "inactive"}},
	}}

	status, observedAt, payload := summarizeNativeGuard(runtime, "node-1")

	if status != "CRITICAL" {
		t.Fatalf("expected CRITICAL, got %q", status)
	}
	if observedAt == nil || observedAt.AsTime().Unix() != collected {
		t.Fatalf("observedAt must carry the collector timestamp, got %v", observedAt)
	}
	if !strings.Contains(payload, "kylin.security.auditd_status") || !strings.Contains(payload, "inactive") {
		t.Fatalf("payload must keep probe items, got %q", payload)
	}
}

func TestSummarizeNativeGuardFailsClosedWithoutProvider(t *testing.T) {
	status, observedAt, payload := summarizeNativeGuard(plainRuntime{}, "node-1")
	if status != "" || observedAt != nil || payload != "" {
		t.Fatalf("runtime without posture provider must report blank status, got %q / %q", status, payload)
	}
}

func TestNativeGuardReporterCachesWithinTTL(t *testing.T) {
	runtime := &postureRuntime{payload: hosttools.NativeGuardPosture{
		Overall: "healthy", CollectedAtUnix: time.Now().Unix(),
	}}
	reporter := newNativeGuardReporter(runtime, "node-1")

	first, _, firstPayload := reporter.report()
	second, _, secondPayload := reporter.report()

	if first != "HEALTHY" || second != "HEALTHY" {
		t.Fatalf("expected HEALTHY twice, got %q / %q", first, second)
	}
	if firstPayload == "" || firstPayload != secondPayload {
		t.Fatalf("cached payload must be reused, got %q / %q", firstPayload, secondPayload)
	}
	if runtime.calls != 1 {
		t.Fatalf("posture collection must be cached within TTL, collected %d times", runtime.calls)
	}
}

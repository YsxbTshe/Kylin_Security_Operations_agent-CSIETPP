package metrics

import (
	"strings"
	"testing"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/safeops"
)

func TestRenderPrometheus(t *testing.T) {
	output := RenderPrometheus(Snapshot{
		Scheduler: safeops.SchedulerSnapshot{
			Running:        []safeops.SchedulerTaskSnapshot{{TaskID: "task-1", ToolName: "kylin.service.restart", RiskCode: "R3", ResourceLocks: []safeops.ResourceLock{{ResourceKey: "service:nginx", LockType: "WRITE_EXCLUSIVE"}}}},
			Queued:         []safeops.SchedulerTaskSnapshot{{TaskID: "task-2", ToolName: "kylin.disk.usage", RiskCode: "R0"}},
			ActiveLocks:    2,
			MaxParallel:    4,
			RunnerPoolSize: 2,
			ActiveRunners:  1,
			Runners: []safeops.RunnerPoolSlot{
				{RunnerID: "runner-1", State: "RUNNING", TaskID: "task-1", CommandID: "cmd-1"},
				{RunnerID: "runner-2", State: "IDLE"},
			},
			Metrics: safeops.SchedulerMetrics{
				TotalStarted:   10,
				TotalCompleted: 8,
				TotalFailed:    1,
				TotalDenied:    1,
				CachedHits:     3,
				ActiveRunners:  1,
			},
		},
		UpdatedUnix: 123,
		Connected:   true,
	})
	for _, expected := range []string{
		"kylin_node_executor_controlstream_connected 1",
		"kylin_node_executor_scheduler_running_tasks 1",
		"kylin_node_executor_scheduler_queued_tasks 1",
		"kylin_node_executor_scheduler_started_total 10",
		"kylin_node_executor_runner_pool_size 2",
		"kylin_node_executor_runner_pool_active 1",
		`kylin_node_executor_runner_pool_slot{runner_id="runner-1",state="RUNNING"} 1`,
		`kylin_node_executor_runner_pool_slot{runner_id="runner-2",state="IDLE"} 0`,
		`kylin_node_executor_scheduler_running_tasks_by_risk{risk_code="R3"} 1`,
		`kylin_node_executor_scheduler_queued_tasks_by_risk{risk_code="R0"} 1`,
		`kylin_node_executor_scheduler_running_tasks_by_tool{tool_name="kylin.service.restart"} 1`,
		`kylin_node_executor_scheduler_active_locks_by_type{lock_type="WRITE_EXCLUSIVE"} 1`,
	} {
		if !strings.Contains(output, expected) {
			t.Fatalf("expected %q in metrics output:\n%s", expected, output)
		}
	}
	for _, removed := range []string{"offline_audit_buffer"} {
		if strings.Contains(output, removed) {
			t.Fatalf("expected legacy metric %q to be gone from metrics output:\n%s", removed, output)
		}
	}
}

func TestFormatAddress(t *testing.T) {
	if got := FormatAddress(19100); got != ":19100" {
		t.Fatalf("unexpected address: %q", got)
	}
	if got := FormatAddress(0); got != "" {
		t.Fatalf("expected disabled address, got %q", got)
	}
}

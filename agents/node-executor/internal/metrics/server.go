package metrics

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/safeops"
)

type Snapshot struct {
	Scheduler   safeops.SchedulerSnapshot
	UpdatedUnix int64
	Connected   bool
}

type Store struct {
	mu       sync.RWMutex
	snapshot Snapshot
}

func NewStore() *Store {
	return &Store{}
}

func (s *Store) Update(snapshot Snapshot) {
	if s == nil {
		return
	}
	if snapshot.UpdatedUnix <= 0 {
		snapshot.UpdatedUnix = time.Now().Unix()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.snapshot = snapshot
}

func (s *Store) Snapshot() Snapshot {
	if s == nil {
		return Snapshot{}
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.snapshot
}

func (s *Store) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/metrics", func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		_, _ = writer.Write([]byte(RenderPrometheus(s.Snapshot())))
	})
	return mux
}

func Run(ctx context.Context, address string, store *Store) error {
	address = strings.TrimSpace(address)
	if address == "" {
		return nil
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return err
	}
	server := &http.Server{Handler: store.Handler(), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()
	err = server.Serve(listener)
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}

func RenderPrometheus(snapshot Snapshot) string {
	var builder strings.Builder
	writeGauge(&builder, "kylin_node_executor_controlstream_connected", "ControlStream connection state.", boolFloat(snapshot.Connected))
	writeGauge(&builder, "kylin_node_executor_metrics_updated_unix", "Unix time for the latest Node Executor metrics snapshot.", float64(snapshot.UpdatedUnix))
	writeGauge(&builder, "kylin_node_executor_scheduler_running_tasks", "Current running Node Executor tasks.", float64(len(snapshot.Scheduler.Running)))
	writeGauge(&builder, "kylin_node_executor_scheduler_queued_tasks", "Current queued Node Executor tasks.", float64(len(snapshot.Scheduler.Queued)))
	writeGauge(&builder, "kylin_node_executor_scheduler_active_locks", "Current Node Executor active resource locks.", float64(snapshot.Scheduler.ActiveLocks))
	writeGauge(&builder, "kylin_node_executor_scheduler_max_parallel", "Configured Node Executor worker slots.", float64(snapshot.Scheduler.MaxParallel))
	writeGauge(&builder, "kylin_node_executor_runner_pool_size", "Configured Node Executor runner pool size.", float64(snapshot.Scheduler.RunnerPoolSize))
	writeGauge(&builder, "kylin_node_executor_runner_pool_active", "Active Node Executor runners.", float64(snapshot.Scheduler.ActiveRunners))
	writeRunnerPoolGauges(&builder, snapshot.Scheduler.Runners)
	writeTaskRiskGauges(&builder, "kylin_node_executor_scheduler_running_tasks_by_risk", "Current running Node Executor tasks by risk code.", snapshot.Scheduler.Running)
	writeTaskRiskGauges(&builder, "kylin_node_executor_scheduler_queued_tasks_by_risk", "Current queued Node Executor tasks by risk code.", snapshot.Scheduler.Queued)
	writeTaskToolGauges(&builder, "kylin_node_executor_scheduler_running_tasks_by_tool", "Current running Node Executor tasks by tool name.", snapshot.Scheduler.Running)
	writeLockTypeGauges(&builder, "kylin_node_executor_scheduler_active_locks_by_type", "Current Node Executor active resource locks by lock type.", snapshot.Scheduler.Running)
	writeCounter(&builder, "kylin_node_executor_scheduler_started_total", "Node Executor tasks started.", float64(snapshot.Scheduler.Metrics.TotalStarted))
	writeCounter(&builder, "kylin_node_executor_scheduler_completed_total", "Node Executor tasks completed.", float64(snapshot.Scheduler.Metrics.TotalCompleted))
	writeCounter(&builder, "kylin_node_executor_scheduler_failed_total", "Node Executor tasks failed.", float64(snapshot.Scheduler.Metrics.TotalFailed))
	writeCounter(&builder, "kylin_node_executor_scheduler_denied_total", "Node Executor tasks denied by local guardrail.", float64(snapshot.Scheduler.Metrics.TotalDenied))
	writeCounter(&builder, "kylin_node_executor_scheduler_cached_hits_total", "Node Executor idempotency cache hits.", float64(snapshot.Scheduler.Metrics.CachedHits))
	return builder.String()
}

func writeRunnerPoolGauges(builder *strings.Builder, runners []safeops.RunnerPoolSlot) {
	if len(runners) == 0 {
		return
	}
	builder.WriteString("# HELP kylin_node_executor_runner_pool_slot Runner pool slot state, 1 when the slot is running.\n")
	builder.WriteString("# TYPE kylin_node_executor_runner_pool_slot gauge\n")
	for _, runner := range runners {
		value := 0
		if strings.EqualFold(runner.State, "RUNNING") {
			value = 1
		}
		builder.WriteString("kylin_node_executor_runner_pool_slot{runner_id=\"")
		builder.WriteString(escapeLabelValue(runner.RunnerID))
		builder.WriteString("\",state=\"")
		builder.WriteString(escapeLabelValue(runner.State))
		builder.WriteString("\"} ")
		builder.WriteString(strconv.Itoa(value))
		builder.WriteByte('\n')
	}
}

func writeTaskRiskGauges(builder *strings.Builder, name string, help string, tasks []safeops.SchedulerTaskSnapshot) {
	counts := map[string]int{}
	for _, task := range tasks {
		risk := strings.TrimSpace(task.RiskCode)
		if risk == "" {
			risk = "UNKNOWN"
		}
		counts[risk]++
	}
	writeCountMap(builder, name, help, "risk_code", counts)
}

func writeTaskToolGauges(builder *strings.Builder, name string, help string, tasks []safeops.SchedulerTaskSnapshot) {
	counts := map[string]int{}
	for _, task := range tasks {
		tool := strings.TrimSpace(task.ToolName)
		if tool == "" {
			tool = "unknown"
		}
		counts[tool]++
	}
	writeCountMap(builder, name, help, "tool_name", counts)
}

func writeLockTypeGauges(builder *strings.Builder, name string, help string, tasks []safeops.SchedulerTaskSnapshot) {
	counts := map[string]int{}
	for _, task := range tasks {
		for _, lock := range task.ResourceLocks {
			lockType := strings.TrimSpace(lock.LockType)
			if lockType == "" {
				lockType = "UNKNOWN"
			}
			counts[lockType]++
		}
	}
	writeCountMap(builder, name, help, "lock_type", counts)
}

func writeCountMap(builder *strings.Builder, name string, help string, labelName string, counts map[string]int) {
	if len(counts) == 0 {
		return
	}
	keys := make([]string, 0, len(counts))
	for key := range counts {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	builder.WriteString("# HELP ")
	builder.WriteString(name)
	builder.WriteByte(' ')
	builder.WriteString(help)
	builder.WriteByte('\n')
	builder.WriteString("# TYPE ")
	builder.WriteString(name)
	builder.WriteString(" gauge\n")
	for _, key := range keys {
		builder.WriteString(name)
		builder.WriteString("{")
		builder.WriteString(labelName)
		builder.WriteString("=\"")
		builder.WriteString(escapeLabelValue(key))
		builder.WriteString("\"} ")
		builder.WriteString(strconv.Itoa(counts[key]))
		builder.WriteByte('\n')
	}
}

func escapeLabelValue(value string) string {
	value = strings.ReplaceAll(value, "\\", "\\\\")
	value = strings.ReplaceAll(value, "\n", "\\n")
	value = strings.ReplaceAll(value, "\"", "\\\"")
	return value
}

func writeGauge(builder *strings.Builder, name string, help string, value float64) {
	writeMetric(builder, name, "gauge", help, value)
}

func writeCounter(builder *strings.Builder, name string, help string, value float64) {
	writeMetric(builder, name, "counter", help, value)
}

func writeMetric(builder *strings.Builder, name string, metricType string, help string, value float64) {
	builder.WriteString("# HELP ")
	builder.WriteString(name)
	builder.WriteByte(' ')
	builder.WriteString(help)
	builder.WriteByte('\n')
	builder.WriteString("# TYPE ")
	builder.WriteString(name)
	builder.WriteByte(' ')
	builder.WriteString(metricType)
	builder.WriteByte('\n')
	builder.WriteString(name)
	builder.WriteByte(' ')
	builder.WriteString(strconv.FormatFloat(value, 'f', -1, 64))
	builder.WriteByte('\n')
}

func boolFloat(value bool) float64 {
	if value {
		return 1
	}
	return 0
}

func FormatAddress(port int) string {
	if port <= 0 {
		return ""
	}
	return fmt.Sprintf(":%d", port)
}

package safeops

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"sync"
)

type Scheduler struct {
	runner      Runner
	mu          sync.Mutex
	locks       map[string]lockState
	completed   map[string]SafeToolResult
	cancelled   map[string]bool
	paused      map[string]bool
	running     map[string]SafeToolCall
	queued      map[string]SafeToolCall
	taskRunners map[string]string
	metrics     SchedulerMetrics
	store       SchedulerStateStore
	maxParallel int
	slots       chan struct{}
	runnerPool  chan string
	runners     []RunnerPoolSlot
}

type SchedulerMetrics struct {
	TotalStarted   int64 `json:"totalStarted"`
	TotalCompleted int64 `json:"totalCompleted"`
	TotalFailed    int64 `json:"totalFailed"`
	TotalDenied    int64 `json:"totalDenied"`
	CachedHits     int64 `json:"cachedHits"`
	ActiveLocks    int64 `json:"activeLocks"`
	ActiveRunners  int64 `json:"activeRunners"`
}

type SchedulerSnapshot struct {
	Running        []SchedulerTaskSnapshot `json:"running"`
	Queued         []SchedulerTaskSnapshot `json:"queued"`
	Paused         []string                `json:"paused"`
	Cancelled      []string                `json:"cancelled"`
	CompletedCount int                     `json:"completedCount"`
	ActiveLocks    int                     `json:"activeLocks"`
	MaxParallel    int                     `json:"maxParallel"`
	RunnerPoolSize int                     `json:"runnerPoolSize"`
	ActiveRunners  int                     `json:"activeRunners"`
	Runners        []RunnerPoolSlot        `json:"runners"`
	Metrics        SchedulerMetrics        `json:"metrics"`
}

type SchedulerTaskSnapshot struct {
	CommandID     string         `json:"commandId"`
	TaskID        string         `json:"taskId"`
	NodeID        string         `json:"nodeId"`
	ToolName      string         `json:"toolName"`
	RiskCode      string         `json:"riskCode"`
	TraceID       string         `json:"traceId"`
	RunnerID      string         `json:"runnerId"`
	ResourceLocks []ResourceLock `json:"resourceLocks"`
}

type RunnerPoolSlot struct {
	RunnerID  string `json:"runnerId"`
	State     string `json:"state"`
	TaskID    string `json:"taskId,omitempty"`
	CommandID string `json:"commandId,omitempty"`
	ToolName  string `json:"toolName,omitempty"`
	RiskCode  string `json:"riskCode,omitempty"`
}

type lockState struct {
	writer  bool
	readers int
}

func NewScheduler(runner Runner, stores ...SchedulerStateStore) *Scheduler {
	return NewSchedulerWithMaxParallel(runner, 1, stores...)
}

func NewSchedulerWithMaxParallel(runner Runner, maxParallel int, stores ...SchedulerStateStore) *Scheduler {
	return NewSchedulerWithRunnerPool(runner, maxParallel, maxParallel, stores...)
}

func NewSchedulerWithRunnerPool(runner Runner, maxParallel int, runnerPoolSize int, stores ...SchedulerStateStore) *Scheduler {
	if maxParallel <= 0 {
		maxParallel = 1
	}
	if runnerPoolSize <= 0 {
		runnerPoolSize = maxParallel
	}
	scheduler := &Scheduler{
		runner:      runner,
		locks:       map[string]lockState{},
		completed:   map[string]SafeToolResult{},
		cancelled:   map[string]bool{},
		paused:      map[string]bool{},
		running:     map[string]SafeToolCall{},
		queued:      map[string]SafeToolCall{},
		taskRunners: map[string]string{},
		maxParallel: maxParallel,
		slots:       make(chan struct{}, maxParallel),
		runnerPool:  make(chan string, runnerPoolSize),
		runners:     make([]RunnerPoolSlot, 0, runnerPoolSize),
	}
	for i := 1; i <= runnerPoolSize; i++ {
		runnerID := fmt.Sprintf("runner-%d", i)
		scheduler.runnerPool <- runnerID
		scheduler.runners = append(scheduler.runners, RunnerPoolSlot{RunnerID: runnerID, State: "IDLE"})
	}
	if len(stores) > 0 {
		scheduler.store = stores[0]
	}
	scheduler.loadState()
	return scheduler
}

func (s *Scheduler) ExecuteReport(ctx context.Context, call SafeToolCall) ExecutionReport {
	if cached, ok := s.cachedResult(call); ok {
		s.mu.Lock()
		s.metrics.CachedHits++
		s.saveLocked()
		s.mu.Unlock()
		return ExecutionReport{Result: cached, Events: []RuntimeEvent{eventWithTrace(call, "TASK_COMPLETED", call.RiskCode, marshal(map[string]any{"commandId": call.CommandID, "status": "CACHED"}))}}
	}
	key := commandKey(call)
	s.storePending(key, call)
	if err := s.acquireSlot(ctx, key); err != nil {
		result := SafeToolResult{CommandID: call.CommandID, TaskID: call.TaskID, Status: "FAILED", ResultJSON: marshal(map[string]any{"error": err.Error()}), Message: err.Error(), LocalGuardrailDecision: "DENY"}
		return ExecutionReport{Result: result, Events: []RuntimeEvent{
			eventWithTrace(call, "TASK_STARTED", call.RiskCode, marshal(map[string]any{"commandId": call.CommandID, "toolName": call.ToolName})),
			eventWithTrace(call, "TASK_FAILED", call.RiskCode, marshal(map[string]any{"reason": err.Error()})),
		}}
	}
	defer s.releaseSlot()
	runnerID, err := s.acquireRunner(ctx, key)
	if err != nil {
		result := SafeToolResult{CommandID: call.CommandID, TaskID: call.TaskID, Status: "FAILED", ResultJSON: marshal(map[string]any{"error": err.Error()}), Message: err.Error(), LocalGuardrailDecision: "DENY"}
		return ExecutionReport{Result: result, Events: []RuntimeEvent{
			eventWithTrace(call, "TASK_STARTED", call.RiskCode, marshal(map[string]any{"commandId": call.CommandID, "toolName": call.ToolName})),
			eventWithTrace(call, "TASK_FAILED", call.RiskCode, marshal(map[string]any{"reason": err.Error()})),
		}}
	}
	defer s.releaseRunner(runnerID)
	if err := s.beginRunning(key, call, runnerID); err != nil {
		result := SafeToolResult{CommandID: call.CommandID, TaskID: call.TaskID, Status: "DENIED", ResultJSON: marshal(map[string]any{"error": err.Error()}), Message: err.Error(), LocalGuardrailDecision: "DENY"}
		s.recordDenied(key)
		return ExecutionReport{Result: result, Events: []RuntimeEvent{
			eventWithTrace(call, "TASK_STARTED", call.RiskCode, marshal(map[string]any{"commandId": call.CommandID, "toolName": call.ToolName})),
			eventWithTrace(call, "LOCAL_GUARDRAIL_CHECKED", call.RiskCode, marshal(map[string]any{"decision": "DENY", "error": err.Error(), "toolName": call.ToolName})),
			eventWithTrace(call, "TASK_FAILED", call.RiskCode, marshal(map[string]any{"reason": err.Error()})),
		}}
	}
	report := s.runner.ExecuteReport(ctx, call)
	s.finishRunning(key, call, report.Result)
	return report
}

func (s *Scheduler) Cancel(taskID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cancelled[strings.TrimSpace(taskID)] = true
	s.saveLocked()
}

func (s *Scheduler) Pause(taskID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.paused[strings.TrimSpace(taskID)] = true
	s.saveLocked()
}

func (s *Scheduler) Resume(taskID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.paused, strings.TrimSpace(taskID))
	delete(s.cancelled, strings.TrimSpace(taskID))
	s.saveLocked()
}

func (s *Scheduler) Snapshot() SchedulerSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	metrics := s.metrics
	metrics.ActiveLocks = int64(len(s.locks))
	metrics.ActiveRunners = int64(len(s.taskRunners))
	return SchedulerSnapshot{
		Running:        sortedCalls(s.running, s.taskRunners),
		Queued:         sortedCalls(s.queued),
		Paused:         sortedKeys(s.paused),
		Cancelled:      sortedKeys(s.cancelled),
		CompletedCount: len(s.completed),
		ActiveLocks:    len(s.locks),
		MaxParallel:    s.maxParallel,
		RunnerPoolSize: len(s.runners),
		ActiveRunners:  len(s.taskRunners),
		Runners:        sortedRunners(s.runners),
		Metrics:        metrics,
	}
}

func (s *Scheduler) cachedResult(call SafeToolCall) (SafeToolResult, bool) {
	keys := []string{strings.TrimSpace(call.IdempotencyKey), commandKey(call)}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, key := range keys {
		if key == "" {
			continue
		}
		result, ok := s.completed[key]
		if ok {
			return result, true
		}
	}
	return SafeToolResult{}, false
}

func (s *Scheduler) StorePending(call SafeToolCall) bool {
	return s.storePending(commandKey(call), call)
}

func (s *Scheduler) PendingCalls() []SafeToolCall {
	s.mu.Lock()
	defer s.mu.Unlock()
	keys := make([]string, 0, len(s.queued))
	for key := range s.queued {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]SafeToolCall, 0, len(keys))
	for _, key := range keys {
		result = append(result, s.queued[key])
	}
	return result
}

// Lookup returns the durable scheduler view used by ControlStream V2 duplicate handling
// and reconnect reconciliation. A duplicate submit must never start a second execution.
func (s *Scheduler) Lookup(commandID string, idempotencyKey string) (string, SafeToolCall, SafeToolResult, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, key := range []string{strings.TrimSpace(idempotencyKey), strings.TrimSpace(commandID)} {
		if key == "" {
			continue
		}
		if result, ok := s.completed[key]; ok {
			return "COMPLETED", SafeToolCall{}, result, true
		}
	}
	key := strings.TrimSpace(commandID)
	if call, ok := s.running[key]; ok {
		return "RUNNING", call, SafeToolResult{}, true
	}
	if call, ok := s.queued[key]; ok {
		return "QUEUED", call, SafeToolResult{}, true
	}
	return "", SafeToolCall{}, SafeToolResult{}, false
}

func (s *Scheduler) storePending(key string, call SafeToolCall) bool {
	key = strings.TrimSpace(key)
	if key == "" {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.completed[key]; ok {
		return false
	}
	if idempotencyKey := strings.TrimSpace(call.IdempotencyKey); idempotencyKey != "" {
		if _, ok := s.completed[idempotencyKey]; ok {
			return false
		}
	}
	if _, ok := s.running[key]; ok {
		return false
	}
	if _, ok := s.queued[key]; ok {
		return false
	}
	s.queued[key] = call
	s.saveLocked()
	return true
}

func (s *Scheduler) acquireSlot(ctx context.Context, key string) error {
	select {
	case s.slots <- struct{}{}:
		return nil
	case <-ctx.Done():
		s.mu.Lock()
		delete(s.queued, key)
		s.metrics.TotalFailed++
		s.saveLocked()
		s.mu.Unlock()
		return ctx.Err()
	}
}

func (s *Scheduler) releaseSlot() {
	<-s.slots
}

func (s *Scheduler) acquireRunner(ctx context.Context, key string) (string, error) {
	select {
	case runnerID := <-s.runnerPool:
		return runnerID, nil
	case <-ctx.Done():
		s.mu.Lock()
		delete(s.queued, key)
		s.metrics.TotalFailed++
		s.saveLocked()
		s.mu.Unlock()
		return "", ctx.Err()
	}
}

func (s *Scheduler) releaseRunner(runnerID string) {
	runnerID = strings.TrimSpace(runnerID)
	if runnerID == "" {
		return
	}
	s.runnerPool <- runnerID
}

func (s *Scheduler) beginRunning(key string, call SafeToolCall, runnerID string) error {
	taskID := strings.TrimSpace(call.TaskID)
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.cancelled[taskID] {
		delete(s.queued, key)
		return fmt.Errorf("task %s is cancelled", taskID)
	}
	if s.paused[taskID] {
		delete(s.queued, key)
		return fmt.Errorf("task %s is paused", taskID)
	}
	for _, lock := range call.ResourceLocks {
		resourceKey := strings.TrimSpace(lock.ResourceKey)
		if resourceKey == "" {
			continue
		}
		state := s.locks[resourceKey]
		lockType := strings.TrimSpace(lock.LockType)
		if lockType == "WRITE_EXCLUSIVE" {
			if state.writer || state.readers > 0 {
				delete(s.queued, key)
				return fmt.Errorf("resource %s is locked", resourceKey)
			}
			continue
		}
		if state.writer {
			delete(s.queued, key)
			return fmt.Errorf("resource %s is locked for write", resourceKey)
		}
	}
	for _, lock := range call.ResourceLocks {
		resourceKey := strings.TrimSpace(lock.ResourceKey)
		if resourceKey == "" {
			continue
		}
		state := s.locks[resourceKey]
		if strings.TrimSpace(lock.LockType) == "WRITE_EXCLUSIVE" {
			state.writer = true
		} else {
			state.readers++
		}
		s.locks[resourceKey] = state
	}
	delete(s.queued, key)
	s.running[key] = call
	s.taskRunners[key] = runnerID
	s.markRunnerLocked(runnerID, "RUNNING", call)
	s.metrics.TotalStarted++
	s.metrics.ActiveLocks = int64(len(s.locks))
	s.metrics.ActiveRunners = int64(len(s.taskRunners))
	s.saveLocked()
	return nil
}

func (s *Scheduler) recordDenied(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.queued, key)
	delete(s.running, key)
	runnerID := s.taskRunners[key]
	delete(s.taskRunners, key)
	s.markRunnerLocked(runnerID, "IDLE", SafeToolCall{})
	s.metrics.TotalDenied++
	s.metrics.ActiveLocks = int64(len(s.locks))
	s.metrics.ActiveRunners = int64(len(s.taskRunners))
	s.saveLocked()
}

func (s *Scheduler) finishRunning(key string, call SafeToolCall, result SafeToolResult) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, lock := range call.ResourceLocks {
		resourceKey := strings.TrimSpace(lock.ResourceKey)
		if resourceKey == "" {
			continue
		}
		state := s.locks[resourceKey]
		if strings.TrimSpace(lock.LockType) == "WRITE_EXCLUSIVE" {
			state.writer = false
		} else if state.readers > 0 {
			state.readers--
		}
		if !state.writer && state.readers == 0 {
			delete(s.locks, resourceKey)
		} else {
			s.locks[resourceKey] = state
		}
	}
	delete(s.running, key)
	runnerID := s.taskRunners[key]
	delete(s.taskRunners, key)
	s.markRunnerLocked(runnerID, "IDLE", SafeToolCall{})
	switch strings.ToUpper(strings.TrimSpace(result.Status)) {
	case "COMPLETED":
		s.metrics.TotalCompleted++
	case "DENIED":
		s.metrics.TotalDenied++
	default:
		s.metrics.TotalFailed++
	}
	if result.Status == "COMPLETED" {
		if strings.TrimSpace(call.IdempotencyKey) != "" {
			s.completed[call.IdempotencyKey] = result
		}
		s.completed[commandKey(call)] = result
	}
	s.metrics.ActiveLocks = int64(len(s.locks))
	s.metrics.ActiveRunners = int64(len(s.taskRunners))
	s.saveLocked()
}

func (s *Scheduler) markRunnerLocked(runnerID string, state string, call SafeToolCall) {
	runnerID = strings.TrimSpace(runnerID)
	if runnerID == "" {
		return
	}
	for i := range s.runners {
		if s.runners[i].RunnerID != runnerID {
			continue
		}
		s.runners[i].State = state
		if state == "RUNNING" {
			s.runners[i].TaskID = call.TaskID
			s.runners[i].CommandID = call.CommandID
			s.runners[i].ToolName = call.ToolName
			s.runners[i].RiskCode = call.RiskCode
		} else {
			s.runners[i].TaskID = ""
			s.runners[i].CommandID = ""
			s.runners[i].ToolName = ""
			s.runners[i].RiskCode = ""
		}
		return
	}
}

func (s *Scheduler) loadState() {
	if s.store == nil {
		return
	}
	state, err := s.store.Load()
	if err != nil {
		return
	}
	for key, result := range state.Completed {
		if strings.TrimSpace(key) != "" {
			s.completed[key] = result
		}
	}
	for _, taskID := range state.Cancelled {
		if strings.TrimSpace(taskID) != "" {
			s.cancelled[strings.TrimSpace(taskID)] = true
		}
	}
	for _, taskID := range state.Paused {
		if strings.TrimSpace(taskID) != "" {
			s.paused[strings.TrimSpace(taskID)] = true
		}
	}
	for key, call := range state.Queued {
		if strings.TrimSpace(key) != "" {
			s.queued[key] = call
		}
	}
	for key, call := range state.Running {
		if strings.TrimSpace(key) != "" {
			s.queued[key] = call
		}
	}
	s.metrics = state.Metrics
}

func (s *Scheduler) saveLocked() {
	if s.store == nil {
		return
	}
	state := SchedulerState{
		Completed:   map[string]SafeToolResult{},
		Cancelled:   make([]string, 0, len(s.cancelled)),
		Paused:      make([]string, 0, len(s.paused)),
		Running:     map[string]SafeToolCall{},
		Queued:      map[string]SafeToolCall{},
		Metrics:     s.metrics,
		MaxParallel: s.maxParallel,
	}
	state.Metrics.ActiveLocks = int64(len(s.locks))
	state.Metrics.ActiveRunners = int64(len(s.taskRunners))
	for key, result := range s.completed {
		state.Completed[key] = result
	}
	for taskID := range s.cancelled {
		state.Cancelled = append(state.Cancelled, taskID)
	}
	for taskID := range s.paused {
		state.Paused = append(state.Paused, taskID)
	}
	for key, call := range s.running {
		state.Running[key] = call
	}
	for key, call := range s.queued {
		state.Queued[key] = call
	}
	sort.Strings(state.Cancelled)
	sort.Strings(state.Paused)
	_ = s.store.Save(state)
}

func commandKey(call SafeToolCall) string {
	if value := strings.TrimSpace(call.CommandID); value != "" {
		return value
	}
	if value := strings.TrimSpace(call.IdempotencyKey); value != "" {
		return value
	}
	if value := strings.TrimSpace(call.TaskID); value != "" {
		return value
	}
	return "unknown"
}

func sortedCalls(values map[string]SafeToolCall, runnerIDs ...map[string]string) []SchedulerTaskSnapshot {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]SchedulerTaskSnapshot, 0, len(keys))
	for _, key := range keys {
		call := values[key]
		runnerID := ""
		if len(runnerIDs) > 0 && runnerIDs[0] != nil {
			runnerID = runnerIDs[0][key]
		}
		result = append(result, SchedulerTaskSnapshot{
			CommandID:     call.CommandID,
			TaskID:        call.TaskID,
			NodeID:        call.NodeID,
			ToolName:      call.ToolName,
			RiskCode:      call.RiskCode,
			TraceID:       call.TraceID,
			RunnerID:      runnerID,
			ResourceLocks: call.ResourceLocks,
		})
	}
	return result
}

func sortedRunners(values []RunnerPoolSlot) []RunnerPoolSlot {
	result := append([]RunnerPoolSlot(nil), values...)
	sort.Slice(result, func(i, j int) bool {
		return result[i].RunnerID < result[j].RunnerID
	})
	return result
}

func sortedKeys(values map[string]bool) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

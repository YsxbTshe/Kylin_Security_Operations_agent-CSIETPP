// Package audit 记录工作区操作的本地审计日志（NDJSON）。
//
// 审计条目落本地 NDJSON 文件。控制面异步上报链路尚未接入——原后端预留的
// 上报表从未被写入，已随基线清理移除；后续接入时再定义上报目标。
package audit

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/protocol"
)

const (
	// maxValueBytes 是单个参数明文保留的上限，超过或命中敏感键的只记哈希。
	maxValueBytes = 256
	// maxDigestBytes 是参数摘要总长上限。
	maxDigestBytes = 1024
	// maxErrorBytes 是错误摘要长度上限。
	maxErrorBytes = 512

	PhaseStart = "start"
	PhaseEnd   = "end"

	ResultOK    = "ok"
	ResultError = "error"
)

// sensitiveKeys 无论长短一律只记哈希，不落明文。
var sensitiveKeys = map[string]struct{}{
	"content": {},
	"script":  {},
}

// Entry 是一条审计记录。
type Entry struct {
	Time        string `json:"ts"`
	Phase       string `json:"phase"`
	RequestID   string `json:"requestId"`
	TenantID    string `json:"tenantId"`
	AgentID     string `json:"agentId"`
	WorkspaceID string `json:"workspaceId"`
	Action      string `json:"action"`
	ArgsDigest  string `json:"argsDigest,omitempty"`
	ExitCode    int    `json:"exitCode,omitempty"`
	DurationMS  int64  `json:"durationMs,omitempty"`
	Result      string `json:"result,omitempty"`
	Error       string `json:"error,omitempty"`
}

// Auditor 将审计条目追加写入 NDJSON 文件。
// 并发安全：mutex 串行化 + O_APPEND 单次 Write，保证行不被交错。
type Auditor struct {
	mu   sync.Mutex
	file *os.File
	path string
}

// New 打开（或创建）审计文件。
func New(path string) (*Auditor, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, fmt.Errorf("创建审计目录失败：%w", err)
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		return nil, fmt.Errorf("打开审计文件失败：%w", err)
	}
	return &Auditor{file: f, path: path}, nil
}

// Write 追加一条审计条目。
func (a *Auditor) Write(e Entry) error {
	buf, err := json.Marshal(e)
	if err != nil {
		return fmt.Errorf("编码审计条目失败：%w", err)
	}
	buf = append(buf, '\n')
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, err := a.file.Write(buf); err != nil {
		return fmt.Errorf("写入审计日志失败：%w", err)
	}
	return nil
}

func (a *Auditor) Close() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.file.Close()
}

// DigestArguments 构造参数摘要：
// 敏感键（content/script）与超长字符串只记 SHA-256 哈希与长度，不落明文全文。
func DigestArguments(args map[string]any) string {
	if len(args) == 0 {
		return ""
	}
	keys := make([]string, 0, len(args))
	for k := range args {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	summary := make(map[string]any, len(args))
	for _, k := range keys {
		v := args[k]
		if s, ok := v.(string); ok {
			if _, sensitive := sensitiveKeys[k]; sensitive || len(s) > maxValueBytes {
				sum := sha256.Sum256([]byte(s))
				summary[k] = fmt.Sprintf("sha256:%x(%dB)", sum, len(s))
				continue
			}
		}
		summary[k] = v
	}

	buf, err := json.Marshal(summary)
	if err != nil {
		return ""
	}
	if len(buf) > maxDigestBytes {
		buf = append(buf[:maxDigestBytes], "..."...)
	}
	return string(buf)
}

// NewMiddleware 包装 Handler，在每次请求执行前后各写一条审计条目。
// 审计写入失败不阻断请求执行。
func NewMiddleware(inner protocol.Handler, auditor *Auditor) protocol.Handler {
	return middleware{inner: inner, auditor: auditor}
}

type middleware struct {
	inner   protocol.Handler
	auditor *Auditor
}

func (m middleware) Handle(ctx context.Context, req protocol.Request) protocol.Response {
	base := Entry{
		RequestID:   req.RequestID,
		TenantID:    req.TenantID,
		AgentID:     req.AgentID,
		WorkspaceID: req.WorkspaceID,
		Action:      req.Action,
	}

	startEntry := base
	startEntry.Time = time.Now().UTC().Format(time.RFC3339Nano)
	startEntry.Phase = PhaseStart
	startEntry.ArgsDigest = DigestArguments(req.Arguments)
	_ = m.auditor.Write(startEntry)

	start := time.Now()
	resp := m.inner.Handle(ctx, req)

	endEntry := base
	endEntry.Time = time.Now().UTC().Format(time.RFC3339Nano)
	endEntry.Phase = PhaseEnd
	endEntry.ExitCode = resp.ExitCode
	endEntry.DurationMS = time.Since(start).Milliseconds()
	endEntry.Result = ResultOK
	if resp.Error != "" {
		endEntry.Result = ResultError
		endEntry.Error = resp.Error
		if len(endEntry.Error) > maxErrorBytes {
			endEntry.Error = endEntry.Error[:maxErrorBytes] + "..."
		}
	}
	_ = m.auditor.Write(endEntry)
	return resp
}

package safeops

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

// 工具自身失败时，真实原因只在 MCP CallResult 里；callMCP 返回的 error 是笼统的
// "MCP tool returned error"。此前执行路径只保留了后者，把结果整个丢掉，现场审计
// 事件里就只剩一句没有信息量的话——排查 disk.usage 为什么失败时无从下手。
type failingToolRuntime struct{ message string }

func (r failingToolRuntime) ExecuteTool(string, string, string) (string, error) {
	return "", fmt.Errorf("%s", r.message)
}

func TestExecutionFailurePreservesToolErrorDetail(t *testing.T) {
	const detail = `disk_usage execution failed: sudo: The "no new privileges" flag is set`
	runner := NewRunner(failingToolRuntime{message: detail})

	report := runner.ExecuteReport(t.Context(), SafeToolCall{
		CommandID:     "cmd-detail",
		TaskID:        "task-detail",
		NodeID:        "node-1",
		ToolName:      "kylin.disk.usage",
		ArgumentsJSON: `{}`,
		RiskCode:      "R0",
		TraceID:       "trace-detail",
	})

	if report.Result.Status != "FAILED" {
		t.Fatalf("期望 FAILED，实际 %s", report.Result.Status)
	}
	if !strings.Contains(report.Result.Message, detail) {
		t.Fatalf("Message 丢失了工具报告的原因：%q", report.Result.Message)
	}
	if !strings.Contains(report.Result.ResultJSON, "no new privileges") {
		t.Fatalf("ResultJSON 丢失了工具报告的原因：%q", report.Result.ResultJSON)
	}

	// 详情同样要落到审计事件里——只在返回值里可见等于现场看不到。
	var finished string
	for _, event := range report.Events {
		if event.EventType == "EXECUTION_FINISHED" {
			finished = event.PayloadJSON
		}
	}
	if !strings.Contains(finished, "no new privileges") {
		t.Fatalf("EXECUTION_FINISHED 事件丢失了失败原因：%q", finished)
	}
}

func TestToolErrorDetailReadsBothCallResultShapes(t *testing.T) {
	structured, err := json.Marshal(map[string]any{
		"structuredContent": map[string]any{"error": "structured reason"},
		"content":           []map[string]any{{"type": "text", "text": "content reason"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := toolErrorDetail(string(structured)); got != "structured reason" {
		t.Fatalf("应优先取 structuredContent.error，实际 %q", got)
	}

	contentOnly, err := json.Marshal(map[string]any{
		"content": []map[string]any{{"type": "text", "text": "content reason"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := toolErrorDetail(string(contentOnly)); got != "content reason" {
		t.Fatalf("应回退到 content 文本块，实际 %q", got)
	}

	for _, malformed := range []string{"", "   ", "not json"} {
		if got := toolErrorDetail(malformed); got != "" {
			t.Fatalf("无法解析时应返回空串由调用方回退，输入 %q 得到 %q", malformed, got)
		}
	}
}

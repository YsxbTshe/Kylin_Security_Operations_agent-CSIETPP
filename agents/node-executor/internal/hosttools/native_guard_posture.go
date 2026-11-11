package hosttools

import (
	"encoding/json"
	"strings"
	"time"
)

type NativeGuardPosture struct {
	Overall         string                   `json:"overall"`
	CollectedAtUnix int64                    `json:"collectedAtUnix"`
	Items           []NativeGuardPostureItem `json:"items"`
	CriticalCount   int                      `json:"criticalCount"`
	WarningCount    int                      `json:"warningCount"`
}

type NativeGuardPostureItem struct {
	Tool      string `json:"tool"`
	Supported bool   `json:"supported"`
	Status    string `json:"status"`
	Message   string `json:"message"`
}

type nativeGuardToolOutput struct {
	Tool      string `json:"tool"`
	Supported bool   `json:"supported"`
	Status    string `json:"status"`
	Message   string `json:"message"`
}

func (s Service) NativeGuardPosture(nodeID string) NativeGuardPosture {
	tools := []string{
		"kylin.security.kysec_status",
		"kylin.security.selinux_status",
		"kylin.security.auditd_status",
		"kylin.security.security_switch_status",
	}
	items := make([]NativeGuardPostureItem, 0, len(tools))
	for _, tool := range tools {
		output, err := s.ExecuteTool(nodeID, tool, "{}")
		if err != nil {
			items = append(items, NativeGuardPostureItem{Tool: tool, Supported: true, Status: "error", Message: err.Error()})
			continue
		}
		var parsed nativeGuardToolOutput
		if err := json.Unmarshal([]byte(output), &parsed); err != nil {
			items = append(items, NativeGuardPostureItem{Tool: tool, Supported: true, Status: "error", Message: "invalid native guard output"})
			continue
		}
		items = append(items, NativeGuardPostureItem{Tool: parsed.Tool, Supported: parsed.Supported, Status: normalizeGuardStatus(parsed.Status), Message: parsed.Message})
	}
	critical, warning := nativeGuardCounts(items)
	return NativeGuardPosture{
		Overall: nativeGuardOverall(items), CollectedAtUnix: time.Now().Unix(), Items: items,
		CriticalCount: critical, WarningCount: warning,
	}
}

func (s Service) NativeGuardPosturePayload(nodeID string) any {
	return s.NativeGuardPosture(nodeID)
}

func normalizeGuardStatus(status string) string {
	status = strings.ToLower(strings.TrimSpace(status))
	if status == "" {
		return "unknown"
	}
	return status
}

func nativeGuardCounts(items []NativeGuardPostureItem) (critical int, warning int) {
	for _, item := range items {
		if !item.Supported {
			continue
		}
		switch item.Status {
		case "disabled", "inactive", "failed", "off", "error":
			critical++
		case "permissive", "unknown":
			warning++
		}
	}
	return critical, warning
}

func nativeGuardOverall(items []NativeGuardPostureItem) string {
	supported := 0
	for _, item := range items {
		if !item.Supported {
			continue
		}
		supported++
		switch item.Status {
		case "disabled", "inactive", "failed", "off", "error":
			return "critical"
		case "permissive", "unknown":
			return "warning"
		}
	}
	if supported == 0 {
		return "unsupported"
	}
	return "healthy"
}

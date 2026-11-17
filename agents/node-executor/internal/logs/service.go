package logs

import (
	"bufio"
	"os/exec"
	"strings"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/pkg/model"
)

type Service struct{}

func NewService() Service {
	return Service{}
}

func (Service) CollectRecentEntries(nodeID string) []model.LogEntry {
	command := exec.Command("journalctl", "-n", "20", "--no-pager", "--output=short")
	output, err := command.Output()
	if err != nil {
		return []model.LogEntry{
			{NodeID: nodeID, Level: "WARN", Content: "journalctl unavailable: " + err.Error()},
		}
	}

	entries := make([]model.LogEntry, 0, 20)
	scanner := bufio.NewScanner(strings.NewReader(string(output)))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		level := "INFO"
		lower := strings.ToLower(line)
		switch {
		case strings.Contains(lower, "error"), strings.Contains(lower, "fail"):
			level = "ERROR"
		case strings.Contains(lower, "warn"):
			level = "WARN"
		}
		entries = append(entries, model.LogEntry{
			NodeID:  nodeID,
			Level:   level,
			Content: line,
		})
	}
	if len(entries) == 0 {
		return []model.LogEntry{{NodeID: nodeID, Level: "INFO", Content: "journalctl returned no entries"}}
	}
	return entries
}

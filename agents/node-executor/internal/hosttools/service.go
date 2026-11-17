package hosttools

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/baseline"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/collector"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/executor"
	logservice "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/logs"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/nodeexporter"
)

type Service struct {
	collector    collector.Service
	logs         logservice.Service
	baseline     baseline.Service
	executor     executor.Service
	nodeExporter *nodeexporter.Client
	maxLogLines  int
}

func NewService(
	collector collector.Service,
	logs logservice.Service,
	baseline baseline.Service,
	executor executor.Service,
	nodeExporter *nodeexporter.Client,
	maxLogLines int,
) Service {
	return Service{
		collector:    collector,
		logs:         logs,
		baseline:     baseline,
		executor:     executor,
		nodeExporter: nodeExporter,
		maxLogLines:  maxLogLines,
	}
}

func (s Service) RecentLogPayload(nodeID string) any {
	return s.logs.CollectRecentEntries(nodeID)
}

func (s Service) NodeExporterPayload(string) any {
	if s.nodeExporter == nil {
		return nodeexporter.NewClient("").Collect(context.Background())
	}
	return s.nodeExporter.Collect(context.Background())
}

func (s Service) BuildContextSummary(nodeID string) string {
	snapshot := s.collector.CollectSystemSnapshot(nodeID)
	recentLogs := s.logs.CollectRecentEntries(nodeID)
	baselines := s.baseline.RunChecks(nodeID)

	logSummary := make([]string, 0, len(recentLogs))
	for _, item := range recentLogs {
		logSummary = append(logSummary, fmt.Sprintf("%s:%s", item.Level, item.Content))
	}

	baselineSummary := make([]string, 0, len(baselines))
	for _, item := range baselines {
		baselineSummary = append(baselineSummary, fmt.Sprintf("%s=%s", item.Name, item.Status))
	}

	return strings.Join([]string{
		fmt.Sprintf("host=%s ip=%s os=%s cpu=%s memory=%s disk=%s load=%s uptime=%s", snapshot.Hostname, snapshot.IPAddress, snapshot.OSVersion, snapshot.CPUUsage, snapshot.MemoryUsage, snapshot.DiskUsage, snapshot.LoadAverage, snapshot.UptimeText),
		"logs=" + strings.Join(logSummary, " | "),
		"baselines=" + strings.Join(baselineSummary, " | "),
	}, " ; ")
}

func (s Service) ExecuteTool(nodeID string, toolName string, argumentsJSON string) (string, error) {
	toolName = internalToolName(toolName)
	if toolName == "get_recent_logs" {
		entries := s.logs.CollectRecentEntries(nodeID)
		if value := strings.TrimSpace(argumentsJSON); value != "" {
			var arguments map[string]any
			if err := json.Unmarshal([]byte(value), &arguments); err != nil {
				return "", fmt.Errorf("parse tool arguments: %w", err)
			}
			if rawLines, ok := arguments["lines"]; ok {
				lines, err := strconv.Atoi(strings.TrimSpace(fmt.Sprint(rawLines)))
				if err != nil || lines <= 0 {
					return "", fmt.Errorf("invalid lines for get_recent_logs")
				}
				if s.maxLogLines > 0 && lines < len(entries) {
					entries = entries[:lines]
				}
			}
		}
		lines := make([]string, 0, len(entries))
		for _, entry := range entries {
			lines = append(lines, fmt.Sprintf("%s:%s", entry.Level, entry.Content))
		}
		return strings.Join(lines, " | "), nil
	}
	if toolName == "node_exporter_snapshot" {
		payload, err := json.Marshal(s.NodeExporterPayload(nodeID))
		if err != nil {
			return "", fmt.Errorf("serialize node exporter snapshot: %w", err)
		}
		return string(payload), nil
	}
	if toolName == "diagnosis_triage" {
		payload, err := json.Marshal(map[string]string{
			"tool":    "kylin.diagnosis.triage",
			"summary": s.BuildContextSummary(nodeID),
		})
		if err != nil {
			return "", fmt.Errorf("serialize diagnosis triage: %w", err)
		}
		return string(payload), nil
	}

	var rawArguments map[string]any
	if argumentsJSON != "" {
		if err := json.Unmarshal([]byte(argumentsJSON), &rawArguments); err != nil {
			return "", fmt.Errorf("parse tool arguments: %w", err)
		}
	}
	arguments := map[string]string{}
	for key, value := range rawArguments {
		arguments[key] = strings.TrimSpace(fmt.Sprint(value))
	}
	if arguments == nil {
		arguments = map[string]string{}
	}

	return s.executor.Execute(toolName, arguments)
}

func internalToolName(toolName string) string {
	switch strings.TrimSpace(toolName) {
	case "kylin.disk.usage":
		return "disk_usage"
	case "kylin.disk.large_files":
		return "disk_large_files"
	case "kylin.io.snapshot":
		return "io_snapshot"
	case "kylin.metrics.node_exporter_snapshot":
		return "node_exporter_snapshot"
	case "kylin.process.list":
		return "process_list"
	case "kylin.process.top_cpu":
		return "top_cpu_processes"
	case "kylin.process.top_memory":
		return "top_memory_processes"
	case "kylin.service.status":
		return "service_status"
	case "kylin.service.start":
		return "start_service"
	case "kylin.service.stop":
		return "stop_service"
	case "kylin.service.restart":
		return "restart_service"
	case "kylin.service.reload":
		return "reload_service"
	case "kylin.service.enable":
		return "enable_service"
	case "kylin.service.disable":
		return "disable_service"
	case "kylin.firewall.state":
		return "firewall_state"
	case "kylin.firewall.rules":
		return "firewall_rules"
	case "kylin.firewall.block_ip":
		return "block_ip"
	case "kylin.firewall.unblock_ip":
		return "unblock_ip"
	case "kylin.log.journal_query":
		return "journalctl_query"
	case "kylin.net.listening_ports":
		return "list_listening_ports"
	case "kylin.net.connections":
		return "list_connections"
	case "kylin.file.lsof_path":
		return "lsof_path"
	case "kylin.file.stat":
		return "file_stat"
	case "kylin.file.sm3":
		return "file_sm3"
	case "kylin.system.sysctl_snapshot":
		return "sysctl_snapshot"
	case "kylin.system.mounts":
		return "filesystem_mounts"
	case "kylin.package.list":
		return "list_installed_packages"
	case "kylin.package.info":
		return "package_info"
	case "kylin.user.accounts":
		return "list_user_accounts"
	case "kylin.user.privileged_groups":
		return "list_privileged_groups"
	case "kylin.user.last_logins":
		return "last_logins"
	case "kylin.config.permissions_snapshot":
		return "config_permissions_snapshot"
	case "kylin.diagnosis.triage":
		return "diagnosis_triage"
	case "kylin.cleanup.plan":
		return "cleanup_plan"
	case "kylin.cleanup.execute":
		return "cleanup_execute"
	case "kylin.cleanup.rollback":
		return "cleanup_rollback"
	case "kylin.security.kysec_status":
		return "kysec_status"
	case "kylin.security.selinux_status":
		return "selinux_status"
	case "kylin.security.auditd_status":
		return "auditd_status"
	case "kylin.security.security_switch_status":
		return "security_switch_status"
	default:
		return strings.TrimSpace(toolName)
	}
}

package config

import (
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/privops"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	NodeID                  string
	OrganizationID          string
	ClusterName             string
	GrpcEnrollAddress       string
	GrpcControlAddress      string
	CertDir                 string
	EnrollmentToken         string
	PrivHelperSocket        string
	WorkspaceRunnerSocket   string
	ExecutionUser           string
	AllowedServices         []string
	AllowedBlockScopes      []string
	AllowedPathRoots        []string
	MaxBlockDurationMinutes int
	MaxLogLines             int
	MaxCommandOutputBytes   int
	CommandTimeoutSeconds   int
	SessionDir              string
	SchedulerMaxParallel    int
	RunnerPoolSize          int
	MetricsAddress          string
	NodeExporterURL         string
	OtelEnabled             bool
	OtelEndpoint            string
	OtelInsecure            bool
	OtelServiceName         string
}

// defaultAllowedPathRoots 是执行器自己的路径底线，独立于工具目录里每个工具声明的
// AllowedRoots：目录由控制面下发，不能作为唯一依据，两份清单必须都通过。
//
// /tmp/aiops-demo-logs 是部署方（systemd ReadWritePaths、工具目录）已认可
// 的演示目录，此前漏在这份底线之外，于是目录里宣称可用的工具在执行时被拒，现场只
// 能看到一句 "outside allowed roots"，要翻两处配置才能归因。
var defaultAllowedPathRoots = []string{"/var/log", "/etc", "/usr/lib/systemd/system", "/opt", "/tmp/aiops-demo-logs"}

func Load() Config {
	return Config{
		NodeID:                  envOrDefault("KYLIN_NODE_ID", "node-kylin-01"),
		OrganizationID:          envOrDefault("KYLIN_ORGANIZATION_ID", "org-alpha"),
		ClusterName:             envOrDefault("KYLIN_CLUSTER_NAME", "demo-cluster"),
		GrpcEnrollAddress:       envOrDefault("KYLIN_GRPC_ENROLL_ADDRESS", "127.0.0.1:9091"),
		GrpcControlAddress:      envOrDefault("KYLIN_GRPC_CONTROL_ADDRESS", "127.0.0.1:9090"),
		CertDir:                 envOrDefault("KYLIN_NODE_EXECUTOR_CERT_DIR", "certs"),
		EnrollmentToken:         strings.TrimSpace(os.Getenv("KYLIN_ENROLLMENT_TOKEN")),
		PrivHelperSocket:        envOrDefault("KYLIN_PRIVHELPER_SOCKET", privops.DefaultSocketPath),
		WorkspaceRunnerSocket:   envOrDefault("KYLIN_WORKSPACE_RUNNER_SOCKET", "/run/kylin-workspace/workspace.sock"),
		ExecutionUser:           envOrDefault("KYLIN_EXECUTION_USER", "kylin-agent"),
		AllowedServices:         envListOrDefault("KYLIN_ALLOWED_SERVICES", []string{"business-service", "nginx", "sshd"}),
		AllowedBlockScopes:      envListOrDefault("KYLIN_ALLOWED_BLOCK_SCOPES", []string{"ingress"}),
		AllowedPathRoots:        envListOrDefault("KYLIN_ALLOWED_PATH_ROOTS", defaultAllowedPathRoots),
		MaxBlockDurationMinutes: envIntOrDefault("KYLIN_MAX_BLOCK_DURATION_MINUTES", 60),
		MaxLogLines:             envIntOrDefault("KYLIN_MAX_LOG_LINES", 200),
		MaxCommandOutputBytes:   envIntOrDefault("KYLIN_MAX_COMMAND_OUTPUT_BYTES", 65536),
		CommandTimeoutSeconds:   envIntOrDefault("KYLIN_COMMAND_TIMEOUT_SECONDS", 20),
		SessionDir:              envOrDefault("KYLIN_NODE_EXECUTOR_SESSION_DIR", "sessions"),
		SchedulerMaxParallel:    envIntOrDefault("KYLIN_SCHEDULER_MAX_PARALLEL", 4),
		RunnerPoolSize:          envIntOrDefault("KYLIN_RUNNER_POOL_SIZE", envIntOrDefault("KYLIN_SCHEDULER_MAX_PARALLEL", 4)),
		MetricsAddress:          envOrDefault("KYLIN_NODE_EXECUTOR_METRICS_ADDRESS", "127.0.0.1:19100"),
		NodeExporterURL:         strings.TrimSpace(os.Getenv("KYLIN_NODE_EXPORTER_URL")),
		OtelEnabled:             envBoolOrDefault("KYLIN_OTEL_ENABLED", false),
		OtelEndpoint:            envOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "127.0.0.1:4317"),
		OtelInsecure:            envBoolOrDefault("OTEL_EXPORTER_OTLP_INSECURE", true),
		OtelServiceName:         envOrDefault("OTEL_SERVICE_NAME", "kylin-node-executor"),
	}
}

func envOrDefault(key string, fallback string) string {
	value, ok := os.LookupEnv(key)
	if !ok || value == "" {
		return fallback
	}
	return value
}

func envIntOrDefault(key string, fallback int) int {
	value, ok := os.LookupEnv(key)
	if !ok || value == "" {
		return fallback
	}

	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func envBoolOrDefault(key string, fallback bool) bool {
	value, ok := os.LookupEnv(key)
	if !ok || value == "" {
		return fallback
	}

	switch strings.ToLower(strings.TrimSpace(value)) {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return fallback
	}
}

func envListOrDefault(key string, fallback []string) []string {
	value, ok := os.LookupEnv(key)
	if !ok || strings.TrimSpace(value) == "" {
		return fallback
	}

	parts := strings.Split(value, ",")
	result := make([]string, 0, len(parts))
	for _, part := range parts {
		normalized := strings.TrimSpace(part)
		if normalized != "" {
			result = append(result, normalized)
		}
	}
	if len(result) == 0 {
		return fallback
	}
	return result
}

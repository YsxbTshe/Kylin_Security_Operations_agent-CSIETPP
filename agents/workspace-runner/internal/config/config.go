package config

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

type Config struct {
	SocketPath          string
	PeerUser            string
	PodmanBinary        string
	ContainerImage      string
	WorkspaceRoot       string
	MaxContainers       int
	ContainerTimeoutSec int
	MemoryLimitMB       int
	PidsLimit           int
	TmpfsSizeMB         int
	MaxOutputBytes      int
	CommandTimeoutSec   int
	MaxPathDepth        int
	MaxFileNameLength   int

	// 生命周期管理（Iteration 4.6）
	RecoveryMode        string // cleanup | recreate | keep
	SnapshotKeep        int    // 每个工作区保留的快照数上限
	SnapshotMaxAgeHours int    // 快照最长保留时长（小时）
	IdleTimeoutSec      int    // 工作区空闲超时（秒），超时后回收容器
	AuditEnabled        bool
	AuditLogPath        string
}

func Load() Config {
	workspaceRoot := envOrDefault("KYLIN_WORKSPACE_ROOT", "/var/lib/kylin-workspace")
	return Config{
		SocketPath:          envOrDefault("KYLIN_WORKSPACE_SOCKET", "/run/kylin-workspace/workspace.sock"),
		// 与 privhelper 的 KYLIN_PRIVHELPER_PEER_USER 取同一个默认值：节点上
		// node-executor 就是以 kylin-agent 运行的。填 "any" 可关闭对端校验（仅开发用，
		// 启动时会打警告）；填别的名字或数字 uid 都会在启动时解析，解析不了直接退出。
		PeerUser:            envOrDefault("KYLIN_WORKSPACE_PEER_USER", "kylin-agent"),
		PodmanBinary:        envOrDefault("KYLIN_PODMAN_BINARY", "podman"),
		ContainerImage:      envOrDefault("KYLIN_WORKSPACE_IMAGE", "kylin-workspace:latest"),
		WorkspaceRoot:       workspaceRoot,
		MaxContainers:       envIntOrDefault("KYLIN_WORKSPACE_MAX_CONTAINERS", 32),
		ContainerTimeoutSec: envIntOrDefault("KYLIN_WORKSPACE_CONTAINER_TIMEOUT_SEC", 600),
		MemoryLimitMB:       envIntOrDefault("KYLIN_WORKSPACE_MEMORY_LIMIT_MB", 512),
		PidsLimit:           envIntOrDefault("KYLIN_WORKSPACE_PIDS_LIMIT", 256),
		TmpfsSizeMB:         envIntOrDefault("KYLIN_WORKSPACE_TMPFS_SIZE_MB", 256),
		MaxOutputBytes:      envIntOrDefault("KYLIN_WORKSPACE_MAX_OUTPUT_BYTES", 1<<20),
		CommandTimeoutSec:   envIntOrDefault("KYLIN_WORKSPACE_COMMAND_TIMEOUT_SEC", 300),
		MaxPathDepth:        envIntOrDefault("KYLIN_WORKSPACE_MAX_PATH_DEPTH", 16),
		MaxFileNameLength:   envIntOrDefault("KYLIN_WORKSPACE_MAX_FILE_NAME_LENGTH", 255),

		RecoveryMode:        envOrDefault("KYLIN_WORKSPACE_RECOVERY_MODE", "cleanup"),
		SnapshotKeep:        envIntOrDefault("KYLIN_WORKSPACE_SNAPSHOT_KEEP", 5),
		SnapshotMaxAgeHours: envIntOrDefault("KYLIN_WORKSPACE_SNAPSHOT_MAX_AGE_HOURS", 72),
		IdleTimeoutSec:      envIntOrDefault("KYLIN_WORKSPACE_IDLE_TIMEOUT", 1800),
		AuditEnabled:        envBoolOrDefault("KYLIN_WORKSPACE_AUDIT_ENABLED", true),
		AuditLogPath:        envOrDefault("KYLIN_WORKSPACE_AUDIT_LOG", filepath.Join(workspaceRoot, "audit", "workspace-audit.ndjson")),
	}
}

func envOrDefault(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return fallback
}

func envIntOrDefault(key string, fallback int) int {
	v, ok := os.LookupEnv(key)
	if !ok || v == "" {
		return fallback
	}
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		return fallback
	}
	return n
}

func envBoolOrDefault(key string, fallback bool) bool {
	v, ok := os.LookupEnv(key)
	if !ok || strings.TrimSpace(v) == "" {
		return fallback
	}
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return fallback
	}
}

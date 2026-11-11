package sandbox

// 资源限制常量。所有值都是硬上限，不依赖调用方传入。
// 即使 Node Executor 被攻陷，Workspace Runner 也不会突破这些边界。
const (
	DefaultMemoryLimitMB  = 512
	DefaultPidsLimit      = 256
	DefaultTmpfsSizeMB    = 256
	DefaultTimeoutSec     = 300
	DefaultMaxOutputBytes = 1 << 20
	DefaultMaxPathDepth   = 16
	DefaultMaxFileNameLen = 255

	// containerNamePrefix 用于容器命名，便于审计和清理。
	containerNamePrefix = "kylin-ws"

	// workspaceMountPoint 是容器内的工作目录。
	workspaceMountPoint = "/workspace"
)

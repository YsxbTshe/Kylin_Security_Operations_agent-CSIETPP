package lifecycle

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

// SnapshotImagePrefix 是所有工作区快照镜像的统一前缀，用于过滤与清理。
const SnapshotImagePrefix = "kylin-ws-snap-"

// Snapshotter 用 podman commit 对运行中的工作区容器做 COW 快照。
type Snapshotter struct {
	client PodmanClient
	log    *slog.Logger
}

func NewSnapshotter(client PodmanClient, logger *slog.Logger) *Snapshotter {
	if logger == nil {
		logger = slog.Default()
	}
	return &Snapshotter{client: client, log: logger}
}

// SnapshotImageName 构造快照镜像名。
// 格式：kylin-ws-snap-{tenant}-{agent}-{short12}:{UTC 时间戳}。
func SnapshotImageName(tenantID, agentID, workspaceID string, at time.Time) string {
	short := workspaceID
	if len(short) > 12 {
		short = short[:12]
	}
	return fmt.Sprintf("%s%s-%s-%s:%s", SnapshotImagePrefix,
		sandbox.SanitizeSegment(tenantID), sandbox.SanitizeSegment(agentID), short,
		at.UTC().Format("20060102-150405"))
}

// Take 对工作区容器做快照，返回镜像名。at 显式传入便于测试与调用方对齐时间。
func (s *Snapshotter) Take(ctx context.Context, cfg sandbox.ContainerConfig, at time.Time) (string, error) {
	container := cfg.ContainerName()
	image := SnapshotImageName(cfg.TenantID, cfg.AgentID, cfg.WorkspaceID, at)
	if err := s.client.Commit(ctx, container, image); err != nil {
		return "", fmt.Errorf("工作区快照失败：%w", err)
	}
	s.log.Info("工作区快照已创建", "container", container, "image", image)
	return image, nil
}

// Snapshot 是一条快照记录。
type Snapshot struct {
	Image     string
	CreatedAt time.Time
}

// List 返回所有工作区快照镜像，按创建时间从旧到新排序。
func (s *Snapshotter) List(ctx context.Context) ([]Snapshot, error) {
	images, err := s.client.ListImages(ctx)
	if err != nil {
		return nil, err
	}
	var result []Snapshot
	for _, img := range images {
		if strings.HasPrefix(img.Name, SnapshotImagePrefix) {
			result = append(result, Snapshot{Image: img.Name, CreatedAt: img.CreatedAt})
		}
	}
	sort.Slice(result, func(i, j int) bool {
		return result[i].CreatedAt.Before(result[j].CreatedAt)
	})
	return result, nil
}

// Remove 删除一个快照镜像。
func (s *Snapshotter) Remove(ctx context.Context, image string) error {
	if err := s.client.RemoveImage(ctx, image, false); err != nil {
		return fmt.Errorf("删除快照镜像失败：%w", err)
	}
	s.log.Info("快照已删除", "image", image)
	return nil
}

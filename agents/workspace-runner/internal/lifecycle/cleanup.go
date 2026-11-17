package lifecycle

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/protocol"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

// RetentionPolicy 是快照保留策略。
type RetentionPolicy struct {
	Keep   int           // 每个工作区保留的快照数上限
	MaxAge time.Duration // 快照最长保留时长，0 表示不按时间清理
}

// Cleaner 负责终态清理：会话结束时回收容器、按计划修剪过期快照。
type Cleaner struct {
	client   PodmanClient
	snapshot *Snapshotter
	policy   RetentionPolicy
	log      *slog.Logger
}

func NewCleaner(client PodmanClient, policy RetentionPolicy, logger *slog.Logger) *Cleaner {
	if logger == nil {
		logger = slog.Default()
	}
	return &Cleaner{
		client:   client,
		snapshot: NewSnapshotter(client, logger),
		policy:   policy,
		log:      logger,
	}
}

// EndWorkspace 在工作区会话结束或超时后停止并删除容器。
func (c *Cleaner) EndWorkspace(ctx context.Context, cfg sandbox.ContainerConfig) error {
	return c.EndContainer(ctx, cfg.ContainerName())
}

// EndContainer 停止并删除指定容器。停止失败（如容器已停止）不阻断删除。
func (c *Cleaner) EndContainer(ctx context.Context, name string) error {
	if err := c.client.Stop(ctx, name); err != nil {
		c.log.Warn("停止容器失败（可能已停止或不存在），继续删除", "container", name, "err", err)
	}
	if err := c.client.Remove(ctx, name, true); err != nil {
		return fmt.Errorf("删除容器失败：%w", err)
	}
	c.log.Info("工作区容器已清理", "container", name)
	return nil
}

// PruneSnapshots 按保留策略修剪快照，返回删除数量。
// 规则：先删除超过 MaxAge 的快照；剩余的每个工作区仅保留最新的 Keep 个。
func (c *Cleaner) PruneSnapshots(ctx context.Context, now time.Time) (int, error) {
	snapshots, err := c.snapshot.List(ctx)
	if err != nil {
		return 0, err
	}

	groups := make(map[string][]Snapshot)
	for _, s := range snapshots {
		key := s.Image
		if idx := strings.LastIndex(key, ":"); idx > 0 {
			key = key[:idx]
		}
		groups[key] = append(groups[key], s)
	}

	removed := 0
	for _, snaps := range groups {
		var survivors []Snapshot
		for _, s := range snaps {
			expired := c.policy.MaxAge > 0 && !s.CreatedAt.IsZero() && now.Sub(s.CreatedAt) > c.policy.MaxAge
			if expired && c.removeSnapshot(ctx, s.Image) {
				removed++
				continue
			}
			survivors = append(survivors, s)
		}
		if c.policy.Keep > 0 && len(survivors) > c.policy.Keep {
			for _, s := range survivors[:len(survivors)-c.policy.Keep] {
				if c.removeSnapshot(ctx, s.Image) {
					removed++
				}
			}
		}
	}
	if removed > 0 {
		c.log.Info("快照修剪完成", "removed", removed)
	}
	return removed, nil
}

func (c *Cleaner) removeSnapshot(ctx context.Context, image string) bool {
	if err := c.snapshot.Remove(ctx, image); err != nil {
		c.log.Warn("删除快照失败", "image", image, "err", err)
		return false
	}
	return true
}

// Shutdown 停止并删除本 runner 管理的全部容器，用于优雅关闭。返回清理数量。
func (c *Cleaner) Shutdown(ctx context.Context) (int, error) {
	names, err := c.client.ListContainers(ctx)
	if err != nil {
		return 0, fmt.Errorf("列出工作区容器失败：%w", err)
	}
	cleaned := 0
	for _, name := range names {
		if err := c.EndContainer(ctx, name); err != nil {
			c.log.Warn("关闭时清理容器失败", "container", name, "err", err)
			continue
		}
		cleaned++
	}
	c.log.Info("优雅关闭清理完成", "containers", cleaned)
	return cleaned, nil
}

// Reaper 定期回收空闲超时的工作区容器。
type Reaper struct {
	cleaner     *Cleaner
	idleTimeout time.Duration
	interval    time.Duration
	mu          sync.Mutex
	lastActive  map[string]time.Time
	log         *slog.Logger
}

func NewReaper(cleaner *Cleaner, idleTimeout, interval time.Duration, logger *slog.Logger) *Reaper {
	if logger == nil {
		logger = slog.Default()
	}
	if idleTimeout <= 0 {
		idleTimeout = 30 * time.Minute
	}
	if interval <= 0 {
		interval = time.Minute
	}
	return &Reaper{
		cleaner:     cleaner,
		idleTimeout: idleTimeout,
		interval:    interval,
		lastActive:  make(map[string]time.Time),
		log:         logger,
	}
}

// Touch 记录容器的最近活动时间。
func (r *Reaper) Touch(name string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lastActive[name] = time.Now()
}

// Forget 移除容器的活动记录。
func (r *Reaper) Forget(name string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.lastActive, name)
}

// due 返回在 at 时刻已超过空闲阈值的容器名。
func (r *Reaper) due(at time.Time) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var names []string
	for name, active := range r.lastActive {
		if at.Sub(active) >= r.idleTimeout {
			names = append(names, name)
		}
	}
	return names
}

// Run 阻塞运行回收循环，直到 ctx 取消。
func (r *Reaper) Run(ctx context.Context) {
	ticker := time.NewTicker(r.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			for _, name := range r.due(now) {
				if err := r.cleaner.EndContainer(ctx, name); err != nil {
					r.log.Warn("回收空闲工作区失败", "container", name, "err", err)
					continue
				}
				r.Forget(name)
				r.log.Info("空闲工作区已回收", "container", name, "idleTimeout", r.idleTimeout.String())
			}
		}
	}
}

// NewActivityTracker 包装 Handler，在每次请求时刷新容器活动时间，供 Reaper 判定空闲。
func NewActivityTracker(inner protocol.Handler, reaper *Reaper) protocol.Handler {
	return activityTracker{inner: inner, reaper: reaper}
}

type activityTracker struct {
	inner  protocol.Handler
	reaper *Reaper
}

func (t activityTracker) Handle(ctx context.Context, req protocol.Request) protocol.Response {
	cfg := sandbox.ContainerConfig{
		TenantID:    req.TenantID,
		AgentID:     req.AgentID,
		WorkspaceID: req.WorkspaceID,
	}
	t.reaper.Touch(cfg.ContainerName())
	return t.inner.Handle(ctx, req)
}

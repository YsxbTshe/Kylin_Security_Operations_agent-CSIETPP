// Package lifecycle 实现工作区容器的生命周期管理：
// 进程重启恢复、COW 快照、空闲回收与终态清理。
package lifecycle

import (
	"context"
	"fmt"
	"log/slog"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

// PodmanClient 是 lifecycle 依赖的最小容器操作接口。
// 注入接口便于测试；生产实现是 sandbox.Podman。
type PodmanClient interface {
	ListContainers(ctx context.Context) ([]string, error)
	Inspect(ctx context.Context, name string) (sandbox.ContainerState, error)
	Start(ctx context.Context, name string) error
	Stop(ctx context.Context, name string) error
	Remove(ctx context.Context, name string, force bool) error
	Commit(ctx context.Context, container, image string) error
	ListImages(ctx context.Context) ([]sandbox.ImageInfo, error)
	RemoveImage(ctx context.Context, name string, force bool) error
}

// RecoveryMode 决定对停止/异常容器的处置方式。
type RecoveryMode string

const (
	ModeCleanup  RecoveryMode = "cleanup"  // 删除停止的容器（默认）
	ModeRecreate RecoveryMode = "recreate" // 重启停止的容器，失败则删除
	ModeKeep     RecoveryMode = "keep"     // 仅记录日志，不做处置
)

type RecoveryOptions struct {
	Mode   RecoveryMode
	Logger *slog.Logger
}

// Recovery 在进程启动时扫描本 runner 管理的工作区容器并恢复一致状态。
type Recovery struct {
	client PodmanClient
	mode   RecoveryMode
	log    *slog.Logger
}

func NewRecovery(client PodmanClient, opts RecoveryOptions) *Recovery {
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	mode := opts.Mode
	switch mode {
	case ModeCleanup, ModeRecreate, ModeKeep:
	default:
		mode = ModeCleanup
	}
	return &Recovery{client: client, mode: mode, log: logger}
}

// RecoveryOutcome 汇总一次恢复的结果。
type RecoveryOutcome struct {
	Inspected int
	Kept      int
	Restarted int
	Removed   int
	Failed    int
}

// Run 扫描所有 kylin-ws 容器：running 保留，exited/dead/unknown 按模式处置。
// 仅当列出容器失败时返回错误；单个容器处置失败计入 Failed。
func (r *Recovery) Run(ctx context.Context) (RecoveryOutcome, error) {
	names, err := r.client.ListContainers(ctx)
	if err != nil {
		return RecoveryOutcome{}, fmt.Errorf("列出工作区容器失败：%w", err)
	}

	var out RecoveryOutcome
	for _, name := range names {
		out.Inspected++
		state, err := r.client.Inspect(ctx, name)
		if err != nil {
			out.Failed++
			r.log.Warn("查询容器状态失败", "container", name, "err", err)
			continue
		}
		switch state {
		case sandbox.StateRunning:
			out.Kept++
			r.log.Info("容器运行正常，保留", "container", name)
		case sandbox.StateNone:
			out.Kept++
			r.log.Info("容器已不存在，跳过", "container", name)
		case sandbox.StateStopped, sandbox.StateUnknown:
			r.handleAbnormal(ctx, name, state, &out)
		}
	}
	r.log.Info("恢复流程完成", "mode", string(r.mode),
		"inspected", out.Inspected, "kept", out.Kept,
		"restarted", out.Restarted, "removed", out.Removed, "failed", out.Failed)
	return out, nil
}

func (r *Recovery) handleAbnormal(ctx context.Context, name string, state sandbox.ContainerState, out *RecoveryOutcome) {
	if r.mode == ModeKeep {
		out.Kept++
		r.log.Info("恢复模式为 keep，保留异常容器", "container", name, "state", string(state))
		return
	}
	if r.mode == ModeRecreate {
		if err := r.client.Start(ctx, name); err == nil {
			out.Restarted++
			r.log.Info("容器已重启", "container", name, "state", string(state))
			return
		} else {
			r.log.Warn("重启容器失败，降级为删除", "container", name, "err", err)
		}
	}
	if err := r.client.Remove(ctx, name, true); err != nil {
		out.Failed++
		r.log.Error("删除异常容器失败", "container", name, "state", string(state), "err", err)
		return
	}
	out.Removed++
	r.log.Info("异常容器已删除", "container", name, "state", string(state), "mode", string(r.mode))
}

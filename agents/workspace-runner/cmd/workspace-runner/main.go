package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/audit"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/config"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/lifecycle"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/protocol"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

func main() {
	cfg := config.Load()

	// 对端身份先解析：解析不了就别起——一个"跑起来了但谁都能连"的 runner
	// 比起不来危险得多。
	allowedPeerUID, err := resolvePeerUID(cfg.PeerUser)
	if err != nil {
		log.Fatalf("解析授权调用方失败：%v", err)
	}
	if allowedPeerUID == nil {
		log.Printf("⚠️ KYLIN_WORKSPACE_PEER_USER=any：已关闭 SO_PEERCRED 对端校验，" +
			"本机任何能访问 socket 的用户都可以驱动沙箱。仅限开发环境。")
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	seccompDir := filepath.Join(cfg.WorkspaceRoot, "seccomp")
	podman, err := sandbox.NewPodman(cfg.PodmanBinary, seccompDir)
	if err != nil {
		log.Fatalf("初始化 Podman 失败：%v", err)
	}

	// 启动恢复：按模式处置上次遗留的异常容器。失败不致命，记录后继续。
	recovery := lifecycle.NewRecovery(podman, lifecycle.RecoveryOptions{
		Mode: lifecycle.RecoveryMode(cfg.RecoveryMode),
	})
	if _, err := recovery.Run(ctx); err != nil {
		log.Printf("启动恢复失败（继续运行）：%v", err)
	}

	cleaner := lifecycle.NewCleaner(podman, lifecycle.RetentionPolicy{
		Keep:   cfg.SnapshotKeep,
		MaxAge: time.Duration(cfg.SnapshotMaxAgeHours) * time.Hour,
	}, nil)

	// 空闲回收：会话长时间无活动后清理容器。
	reaper := lifecycle.NewReaper(cleaner, time.Duration(cfg.IdleTimeoutSec)*time.Second, time.Minute, nil)
	go reaper.Run(ctx)

	var handler protocol.Handler = protocol.NewPodmanHandler(podman)
	handler = lifecycle.NewActivityTracker(handler, reaper)
	if cfg.AuditEnabled {
		auditor, err := audit.New(cfg.AuditLogPath)
		if err != nil {
			log.Fatalf("初始化审计日志失败：%v", err)
		}
		defer auditor.Close()
		handler = audit.NewMiddleware(handler, auditor)
	}

	server := protocol.NewServer(protocol.ServerOptions{
		Handler:        handler,
		SocketPath:     cfg.SocketPath,
		AllowedPeerUID: allowedPeerUID,
	})

	log.Printf("workspace-runner 启动，监听 %s", cfg.SocketPath)
	serveErr := server.Serve(ctx)

	// 优雅关闭：停止并删除本 runner 管理的全部容器。
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if _, err := cleaner.Shutdown(shutdownCtx); err != nil {
		log.Printf("优雅关闭清理失败：%v", err)
	}

	if serveErr != nil {
		log.Fatalf("服务退出：%v", serveErr)
	}
	_ = os.Remove(cfg.SocketPath)
}

// resolvePeerUID 把 KYLIN_WORKSPACE_PEER_USER 解析成允许连接的 uid。
// 返回 nil 表示显式关闭校验（值为 "any"）；解析失败返回 error，调用方应直接退出。
func resolvePeerUID(value string) (*uint32, error) {
	name := strings.TrimSpace(value)
	if name == "" {
		return nil, fmt.Errorf("KYLIN_WORKSPACE_PEER_USER 不能为空（填调用方用户名、数字 uid，或 any 关闭校验）")
	}
	if strings.EqualFold(name, "any") {
		return nil, nil
	}
	// 允许直接写数字 uid：容器化或最小系统里可能没有 /etc/passwd 条目。
	if uid, err := strconv.ParseUint(name, 10, 32); err == nil {
		value := uint32(uid)
		return &value, nil
	}
	account, err := user.Lookup(name)
	if err != nil {
		return nil, fmt.Errorf("查找调用方用户 %s 失败：%w", name, err)
	}
	uid, err := strconv.ParseUint(account.Uid, 10, 32)
	if err != nil {
		return nil, fmt.Errorf("用户 %s 的 uid %q 不是数字：%w", name, account.Uid, err)
	}
	parsed := uint32(uid)
	return &parsed, nil
}

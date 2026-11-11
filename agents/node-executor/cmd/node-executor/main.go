package main

import (
	"context"
	"log"
	"os/signal"
	"syscall"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/bootstrap"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/config"
)

func main() {
	cfg := config.Load()
	nodeExecutor := bootstrap.New(cfg)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if err := nodeExecutor.Run(ctx); err != nil {
		log.Fatal(err)
	}
}

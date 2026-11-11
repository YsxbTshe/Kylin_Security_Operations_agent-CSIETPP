package bootstrap

import (
	"context"
	"fmt"
	"log"
	"os"
	"strings"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/baseline"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/cleanupcore"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/collector"
	daemonconfig "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/config"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstreamv2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/executor"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/hosttools"
	logservice "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/logs"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/mcp"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/metrics"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/nodeexporter"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/otelsetup"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/privops"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/safeops"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/workspace"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/pkg/model"
)

type App struct {
	config    daemonconfig.Config
	collector collector.Service
	executor  executor.Service
	logs      logservice.Service
	baseline  baseline.Service
	hostTools hosttools.Service
	catalog   mcp.Catalog
}

func New(cfg daemonconfig.Config) *App {
	// 特权与审批签名材料都不再留在本进程内（ADR-002）。
	//
	// daemon 不持有任何签名材料：若持有，攻陷 daemon 就等于同时拿到签发能力，
	// 可为任意工具自签合法令牌，R3/R4 人工审批便退化成应用层约定。SM2 签发私钥只在
	// 控制面，公钥只由 root 特权助手持有；这里注入的只是一个「把令牌送过去」的客户端。
	// 写操作同理，全部经由这个客户端离开进程。
	privHelper := privops.NewClient(cfg.PrivHelperSocket, 30*time.Second)
	safeops.SetPrivilegedExecutor(privHelper)
	wsClient := workspace.NewClient(cfg.WorkspaceRunnerSocket, 5*time.Minute)
	safeops.SetWorkspaceCaller(wsClient)
	collectorService := collector.NewService()
	executorService := executor.NewService(cfg)
	logService := logservice.NewService()
	baselineService := baseline.NewService()
	hostTools := hosttools.NewService(collectorService, logService, baselineService, executorService, nodeexporter.NewClient(cfg.NodeExporterURL), cfg.MaxLogLines)
	// daemon 也读同一份 root 持有的清理配置，好让只读的 plan 与 root 侧的 execute
	// 对「哪些目录允许清理、保留期多长」给出一致答案。这只是 UX：作用域的强制点
	// 在助手侧（助手独立加载同一份配置），daemon 这份读错了也只会让 plan 更严。
	cleanupReport := cleanupcore.Configure(os.Getenv(cleanupcore.ConfigPathEnv))
	if cleanupReport.FallbackReason != "" {
		log.Printf("cleanup: 配置未生效，plan 使用内置演示目录 —— %s", cleanupReport)
	} else {
		log.Printf("cleanup: %s", cleanupReport)
	}
	catalog := mcp.NewCatalog()
	warnOnPathRootDrift(catalog, executorService)
	return &App{config: cfg, collector: collectorService, executor: executorService, logs: logService, baseline: baselineService, hostTools: hostTools, catalog: catalog}
}

// warnOnPathRootDrift 在启动时比对工具目录声明的路径根与执行器的全局底线。
//
// 两份清单是有意分开的：目录由控制面下发，执行器不能只信它，底线是本地的第二道
// 独立校验。但分开也意味着会漂移——目录宣称某个根可用而底线没有时，工具在目录里
// 看得见、调用却被拒，现场只剩一句 "outside allowed roots"，要翻两处配置才能归因。
// 这里不阻断启动（底线更严是安全的方向），只把不一致点提前喊出来。
func warnOnPathRootDrift(catalog mcp.Catalog, executorService executor.Service) {
	for _, tool := range catalog.Tools() {
		if unsupported := executorService.UnsupportedPathRoots(tool.AllowedRoots); len(unsupported) > 0 {
			log.Printf("catalog: 工具 %s 声明的路径根 %v 不在执行器允许范围内，调用这些路径会被拒绝",
				tool.Name, unsupported)
		}
	}
}

func (a *App) Run(ctx context.Context) error {
	if strings.TrimSpace(a.config.EnrollmentToken) == "" {
		return fmt.Errorf("KYLIN_ENROLLMENT_TOKEN must be configured as bootstrap token")
	}
	shutdownTelemetry, err := otelsetup.Init(ctx, otelsetup.Config{
		Enabled:     a.config.OtelEnabled,
		Endpoint:    a.config.OtelEndpoint,
		Insecure:    a.config.OtelInsecure,
		ServiceName: a.config.OtelServiceName,
	})
	if err != nil {
		return fmt.Errorf("initialize OpenTelemetry: %w", err)
	}
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := shutdownTelemetry(shutdownCtx); err != nil {
			log.Printf("shutdown OpenTelemetry failed: %v", err)
		}
	}()
	system := a.collector.CollectSystemSnapshot(a.config.NodeID)
	log.Printf("node executor starting ControlStream for %s in cluster %s", a.config.NodeID, a.config.ClusterName)
	log.Printf("system snapshot: hostname=%s os=%s cpu=%s memory=%s", system.Hostname, system.OSVersion, system.CPUUsage, system.MemoryUsage)
	metricsStore := metrics.NewStore()
	if strings.TrimSpace(a.config.MetricsAddress) != "" {
		go func() {
			log.Printf("node executor metrics listening on %s", a.config.MetricsAddress)
			if err := metrics.Run(ctx, a.config.MetricsAddress, metricsStore); err != nil && ctx.Err() == nil {
				log.Printf("node executor metrics server stopped: %v", err)
			}
		}()
	}
	client := controlstreamv2.Client{
		EnrollAddress:   a.config.GrpcEnrollAddress,
		ControlAddress:  a.config.GrpcControlAddress,
		EnrollmentToken: a.config.EnrollmentToken,
		TenantID:        a.config.OrganizationID,
		NodeID:          a.config.NodeID,
		CertStore:       controlstreamv2.CertStore{Dir: a.config.CertDir},
		SessionDir:      a.config.SessionDir,
		Runtime:         a.hostTools,
		Catalog:         a.catalog,
		MaxParallel:     a.config.SchedulerMaxParallel,
		RunnerPoolSize:  a.config.RunnerPoolSize,
		MetricsSnapshot: func(snapshot controlstreamv2.MetricsSnapshot) {
			metricsStore.Update(metrics.Snapshot{
				Scheduler:   snapshot.Scheduler,
				UpdatedUnix: snapshot.UpdatedUnix,
				Connected:   snapshot.Connected,
			})
		},
	}
	return client.Run(ctx, func() model.SystemSnapshot { return a.collector.CollectSystemSnapshot(a.config.NodeID) })
}

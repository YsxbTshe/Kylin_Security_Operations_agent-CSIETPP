package com.superskidder.kylinops.backend.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.agent.RuntimeToolDispatcher;
import com.superskidder.kylinops.backend.agentruntime.agent.SubAgentLifecycleService;
import com.superskidder.kylinops.backend.agentruntime.checkpoint.RunCheckpointService;
import com.superskidder.kylinops.backend.agentruntime.connector.ConnectorToolDispatcher;
import com.superskidder.kylinops.backend.agentruntime.context.ContextCompiler;
import com.superskidder.kylinops.backend.agentruntime.context.ContextSummarizer;
import com.superskidder.kylinops.backend.agentruntime.context.ContextWindowPolicy;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.memory.MemoryService;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGateway;
import com.superskidder.kylinops.backend.agentruntime.model.SimpleModelRouter;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.security.DispatchGate;
import com.superskidder.kylinops.backend.agentruntime.tool.ToolRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 运行时租约轮询器。ReAct 循环本体在 {@link AgentRoundExecutor}。
 *
 * <p><b>并发模型</b>：轮询线程只做「租约 + 派发」，run 的完整 ReAct 循环在独立的执行线程池里跑。
 * 此前 poll() 在调度线程里同步跑完整个循环，全平台同一时刻只能执行一个 run —— 而
 * {@code AgentRuntimeStore.leaseNext()} 的 SQL 里早就写了租户并发配额子查询
 * （{@code tenant_runtime_quota.max_concurrent_runs}，默认 8），那段配额因此从来无法兑现。
 * 这里补上执行线程池，配额 SQL 才真正生效。
 *
 * <p>租约本身的并发安全由 SQL 保证：PostgreSQL 上是 {@code for update skip locked}，
 * H2 上退化为 {@code for update}（串行化租约，仍不会重复租同一条）。
 * {@link AgentRoundExecutor} 无 per-run 可变实例字段（全部 final 协作者），可并发调用。
 */
@Component
public class AgentRunWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentRunWorker.class);

    private final boolean enabled;
    private final AgentRuntimeStore store;
    private final AgentRoundExecutor roundExecutor;
    private final AtomicBoolean polling = new AtomicBoolean();
    /** 执行线程池容量；也是本进程同时在跑的 run 数上限。 */
    private final int maxConcurrentRuns;
    private final ExecutorService runExecutor;
    /** 已派发但尚未跑完的 run 数，用于在租约前先看有没有空位，避免租了却没线程跑。 */
    private final AtomicInteger inFlight = new AtomicInteger();

    public AgentRunWorker(
            boolean enabled,
            long nativeGuardMaxAgeMinutes,
            String autoRiskCeiling,
            AgentRuntimeStore store,
            ContextCompiler contextCompiler,
            ContextWindowPolicy windowPolicy,
            ContextSummarizer contextSummarizer,
            SimpleModelRouter modelRouter,
            ModelGateway modelGateway,
            NodeMcpGateway mcpGateway,
            ToolRegistry toolRegistry,
            DispatchGate dispatchGate,
            ApprovalService approvalService,
            RuntimeToolDispatcher runtimeToolDispatcher,
            ConnectorToolDispatcher connectorToolDispatcher,
            SubAgentLifecycleService subAgentLifecycle,
            MemoryService memoryService,
            RunCheckpointService checkpoints,
            ObjectMapper objectMapper
    ) {
        this(enabled, nativeGuardMaxAgeMinutes, autoRiskCeiling, 8, 30L, 8, store, contextCompiler, windowPolicy,
                contextSummarizer, modelRouter, modelGateway, mcpGateway, toolRegistry, dispatchGate,
                approvalService, runtimeToolDispatcher, connectorToolDispatcher, subAgentLifecycle,
                memoryService, checkpoints, objectMapper);
    }

    @Autowired
    public AgentRunWorker(
            @Value("${app.agent-runtime.enabled:true}") boolean enabled,
            @Value("${app.agent-runtime.native-guard-max-age-minutes:5}") long nativeGuardMaxAgeMinutes,
            @Value("${app.agent-runtime.auto-risk-ceiling:R2}") String autoRiskCeiling,
            @Value("${app.agent-runtime.max-agentic-rounds:8}") int maxAgenticRounds,
            @Value("${app.agent-runtime.model-timeout-seconds:30}") long modelTimeoutSeconds,
            @Value("${app.agent-runtime.max-concurrent-runs:8}") int maxConcurrentRuns,
            AgentRuntimeStore store,
            ContextCompiler contextCompiler,
            ContextWindowPolicy windowPolicy,
            ContextSummarizer contextSummarizer,
            SimpleModelRouter modelRouter,
            ModelGateway modelGateway,
            NodeMcpGateway mcpGateway,
            ToolRegistry toolRegistry,
            DispatchGate dispatchGate,
            ApprovalService approvalService,
            RuntimeToolDispatcher runtimeToolDispatcher,
            ConnectorToolDispatcher connectorToolDispatcher,
            SubAgentLifecycleService subAgentLifecycle,
            MemoryService memoryService,
            RunCheckpointService checkpoints,
            ObjectMapper objectMapper
    ) {
        this.enabled = enabled;
        this.store = store;
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.runExecutor = Executors.newFixedThreadPool(this.maxConcurrentRuns, namedDaemonFactory());
        this.roundExecutor = new AgentRoundExecutor(
                maxAgenticRounds, modelTimeoutSeconds, nativeGuardMaxAgeMinutes, autoRiskCeiling,
                store, contextCompiler, windowPolicy, contextSummarizer, modelRouter, modelGateway,
                mcpGateway, toolRegistry, dispatchGate, approvalService, runtimeToolDispatcher,
                connectorToolDispatcher, subAgentLifecycle, memoryService, checkpoints, objectMapper);
    }

    /**
     * 每轮把空位填满：有多少空闲线程就尝试租多少条 run，租不到就停。
     *
     * <p>轮询本身仍是单线程互斥（{@code polling}），保证同一时刻只有一个线程在租约；
     * 但它不再等待 run 跑完，因此调度线程不会被一条长 run 占死，也不会拖住同一个
     * 调度器上的其它 {@code @Scheduled} 清扫任务。
     */
    @Scheduled(fixedDelayString = "${app.agent-runtime.poll-delay-ms:250}")
    public void poll() {
        if (!enabled || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            while (inFlight.get() < maxConcurrentRuns) {
                Optional<LeasedAgentRun> next = store.leaseNext();
                if (next.isEmpty()) {
                    break;
                }
                if (!dispatch(next.get())) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            log.error("AgentRun lease poll failed", exception);
        } finally {
            polling.set(false);
        }
    }

    /** 派发一条已租到的 run；返回 false 表示线程池满，本轮不再继续租。 */
    private boolean dispatch(LeasedAgentRun run) {
        inFlight.incrementAndGet();
        try {
            runExecutor.execute(() -> {
                try {
                    execute(run);
                } catch (RuntimeException exception) {
                    // 单条 run 失败不能影响其它 run；状态收敛由 AgentRoundExecutor 自己落库。
                    log.error("AgentRun {} execution failed", run.id(), exception);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            inFlight.decrementAndGet();
            // 线程池已关闭（停机中）。租约不显式释放：它带 lease_until，到期后自动可被重租，
            // 比在关停路径上再写一次库更安全。
            log.warn("AgentRun {} not dispatched: executor rejected (shutting down?)", run.id());
            return false;
        }
    }

    public void execute(LeasedAgentRun run) {
        roundExecutor.execute(run);
    }

    private static ThreadFactory namedDaemonFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "agent-run-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        runExecutor.shutdown();
        try {
            if (!runExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                runExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            runExecutor.shutdownNow();
        }
    }
}

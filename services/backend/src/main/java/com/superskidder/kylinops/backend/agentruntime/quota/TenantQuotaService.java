package com.superskidder.kylinops.backend.agentruntime.quota;

import com.superskidder.kylinops.backend.agentruntime.agent.SubAgentLifecycleService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 多租户运行时额度（Phase 6.2）：
 * - 并发上限：lease 侧由 AgentRuntimeStore.leaseNext 的额度过滤执行，超限保持 QUEUED 不租出（不丢任务）；
 * - WAITING 超时：低频清扫，超过租户 run_wait_timeout 的 WAITING run 置 CANCELLED，
 *   级联清理未完成子 run 与 OPEN handoff（复用 SubAgentLifecycleService.cancelRun）。
 * 无 tenant_runtime_quota 行时按缺省值：并发 run 8、子代理 4、等待超时 24h。
 */
@Component
public class TenantQuotaService {

    public static final int DEFAULT_MAX_CONCURRENT_RUNS = 8;
    public static final int DEFAULT_MAX_CONCURRENT_SUBAGENTS = 4;
    public static final long DEFAULT_RUN_WAIT_TIMEOUT_SECONDS = 86_400L;

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaService.class);
    private static final int SWEEP_BATCH_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final SubAgentLifecycleService subAgentLifecycle;
    private final boolean waitTimeoutSweepEnabled;

    public TenantQuotaService(
            JdbcTemplate jdbcTemplate,
            SubAgentLifecycleService subAgentLifecycle,
            @Value("${app.runtime.wait-timeout-sweep-enabled:true}") boolean waitTimeoutSweepEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.subAgentLifecycle = subAgentLifecycle;
        this.waitTimeoutSweepEnabled = waitTimeoutSweepEnabled;
    }

    public record TenantQuota(
            String tenantId, int maxConcurrentRuns, int maxConcurrentSubagents, long runWaitTimeoutSeconds
    ) {}

    public record SweepResult(List<String> cancelledRunIds) {}

    /** 租户额度；无配置行时返回缺省值。 */
    public TenantQuota quotaFor(String tenantId) {
        List<TenantQuota> rows = jdbcTemplate.query("""
                select tenant_id, max_concurrent_runs, max_concurrent_subagents, run_wait_timeout_seconds
                from tenant_runtime_quota where tenant_id = ?
                """, (rs, rowNum) -> new TenantQuota(rs.getString("tenant_id"),
                rs.getInt("max_concurrent_runs"), rs.getInt("max_concurrent_subagents"),
                rs.getLong("run_wait_timeout_seconds")), tenantId);
        return rows.isEmpty()
                ? new TenantQuota(tenantId, DEFAULT_MAX_CONCURRENT_RUNS,
                        DEFAULT_MAX_CONCURRENT_SUBAGENTS, DEFAULT_RUN_WAIT_TIMEOUT_SECONDS)
                : rows.get(0);
    }

    /** 覆盖式配置租户额度（先更新，未命中再插入）。 */
    public TenantQuota upsertQuota(
            String tenantId, int maxConcurrentRuns, int maxConcurrentSubagents, long runWaitTimeoutSeconds
    ) {
        if (maxConcurrentRuns < 0 || maxConcurrentSubagents < 0 || runWaitTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("tenant runtime quota must be positive");
        }
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                update tenant_runtime_quota set max_concurrent_runs = ?, max_concurrent_subagents = ?,
                    run_wait_timeout_seconds = ?, updated_at = ?
                where tenant_id = ?
                """, maxConcurrentRuns, maxConcurrentSubagents, runWaitTimeoutSeconds,
                Timestamp.from(now), tenantId);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into tenant_runtime_quota (
                        tenant_id, max_concurrent_runs, max_concurrent_subagents,
                        run_wait_timeout_seconds, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?)
                    """, tenantId, maxConcurrentRuns, maxConcurrentSubagents,
                    runWaitTimeoutSeconds, Timestamp.from(now), Timestamp.from(now));
        }
        return new TenantQuota(tenantId, maxConcurrentRuns, maxConcurrentSubagents, runWaitTimeoutSeconds);
    }

    /** 活跃并发 = 持有有效租约的 RUNNING run（与 leaseNext 的额度口径一致）。 */
    public int countActiveRuns(String tenantId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from agent_run
                where tenant_id = ? and status = 'RUNNING' and lease_until is not null and lease_until >= ?
                """, Integer.class, tenantId, Timestamp.from(Instant.now()));
        return count == null ? 0 : count;
    }

    /** enqueue 侧校验：false 表示新任务应保持 QUEUED 等待额度（任务不丢弃）。 */
    public boolean canLeaseRun(String tenantId) {
        return countActiveRuns(tenantId) < quotaFor(tenantId).maxConcurrentRuns();
    }

    @Scheduled(fixedDelayString = "${app.runtime.wait-timeout-sweep-delay-ms:300000}")
    public void sweepWaitingTimeouts() {
        if (!waitTimeoutSweepEnabled) {
            return;
        }
        try {
            SweepResult result = cancelTimedOutWaitingRuns(Instant.now());
            if (!result.cancelledRunIds().isEmpty()) {
                log.info("WAIT_TIMEOUT sweep cancelled {} waiting run(s): {}",
                        result.cancelledRunIds().size(), result.cancelledRunIds());
            }
        } catch (RuntimeException exception) {
            log.error("WAIT_TIMEOUT sweep failed", exception);
        }
    }

    /** WAITING 超过租户等待超时 → CANCELLED，级联清理子 run/handoff；未超时不动。 */
    public SweepResult cancelTimedOutWaitingRuns(Instant now) {
        record WaitingRun(String id, String tenantId, Instant updatedAt) {}
        List<WaitingRun> waiting = jdbcTemplate.query("""
                select id, tenant_id, updated_at from agent_run
                where status = 'WAITING' order by updated_at asc limit %d
                """.formatted(SWEEP_BATCH_LIMIT), (rs, rowNum) -> new WaitingRun(rs.getString("id"),
                rs.getString("tenant_id"), rs.getTimestamp("updated_at").toInstant()));
        List<String> cancelled = new ArrayList<>();
        for (WaitingRun run : waiting) {
            long timeoutSeconds = quotaFor(run.tenantId()).runWaitTimeoutSeconds();
            if (!run.updatedAt().isBefore(now.minusSeconds(timeoutSeconds))) {
                continue;
            }
            try {
                subAgentLifecycle.cancelRun(run.id(), "RUNTIME", "wait-timeout-sweeper", "WAIT_TIMEOUT");
                cancelled.add(run.id());
            } catch (RuntimeException exception) {
                log.error("WAIT_TIMEOUT cancel failed for run {}", run.id(), exception);
            }
        }
        return new SweepResult(cancelled);
    }
}

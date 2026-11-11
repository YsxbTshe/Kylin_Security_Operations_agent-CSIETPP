package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.quota.TenantQuotaService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 6.2 租户并发额度：超额 run 保持 QUEUED 不租出（不丢任务）；释放后可租出。
 */
class TenantQuotaLeaseTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private TenantQuotaService quotaService;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        quotaService = new TenantQuotaService(jdbc, null, false);
    }

    private String newQueuedRun(String parentRunId, Instant createdAt) {
        String runId = AgentTestFixtures.insertRun(jdbc, "QUEUED", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "QUEUED");
        jdbc.update("update agent_run set root_agent_instance_id = ?, created_at = ? where id = ?",
                instanceId, Timestamp.from(createdAt), runId);
        if (parentRunId != null) {
            jdbc.update("update agent_run set parent_run_id = ? where id = ?", parentRunId, runId);
        }
        return runId;
    }

    @Test
    void defaultsAllowEightConcurrentRunsAndNinthStaysQueuedUntilASlotFrees() {
        List<String> runIds = new ArrayList<>();
        Instant base = Instant.now();
        for (int index = 0; index < 9; index++) {
            runIds.add(newQueuedRun(null, base.plusSeconds(index)));
        }

        List<LeasedAgentRun> leased = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            leased.add(store.leaseNext().orElseThrow());
        }
        assertThat(leased).extracting(LeasedAgentRun::id)
                .containsExactlyElementsOf(runIds.subList(0, 8));

        // 第 9 个 run 超过缺省并发上限（8）：保持 QUEUED，不丢任务
        assertThat(store.leaseNext()).isEmpty();
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?",
                String.class, runIds.get(8))).isEqualTo("QUEUED");
        assertThat(quotaService.canLeaseRun(AgentTestDatabase.TENANT_ID)).isFalse();

        // 释放一个槽位后立即可租出
        store.completeRun(leased.get(0), Map.of("done", true), 0);
        LeasedAgentRun ninth = store.leaseNext().orElseThrow();
        assertThat(ninth.id()).isEqualTo(runIds.get(8));
        assertThat(quotaService.canLeaseRun(AgentTestDatabase.TENANT_ID)).isFalse();
    }

    @Test
    void customQuotaOfOneKeepsSecondRunQueued() {
        quotaService.upsertQuota(AgentTestDatabase.TENANT_ID, 1, 4, 86_400);
        Instant base = Instant.now();
        String first = newQueuedRun(null, base);
        String second = newQueuedRun(null, base.plusSeconds(1));

        LeasedAgentRun leasedFirst = store.leaseNext().orElseThrow();
        assertThat(leasedFirst.id()).isEqualTo(first);
        assertThat(store.leaseNext()).isEmpty();
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?",
                String.class, second)).isEqualTo("QUEUED");

        store.completeRun(leasedFirst, Map.of("done", true), 0);
        assertThat(store.leaseNext().orElseThrow().id()).isEqualTo(second);
    }

    @Test
    void subAgentConcurrencyCapBlocksThirdChildRun() {
        quotaService.upsertQuota(AgentTestDatabase.TENANT_ID, 8, 1, 86_400);
        Instant base = Instant.now();
        String root = newQueuedRun(null, base);
        String childOne = newQueuedRun(root, base.plusSeconds(1));
        String childTwo = newQueuedRun(root, base.plusSeconds(2));

        assertThat(store.leaseNext().orElseThrow().id()).isEqualTo(root);
        LeasedAgentRun firstChild = store.leaseNext().orElseThrow();
        assertThat(firstChild.id()).isEqualTo(childOne);

        // 子代理并发上限 1：第二个子 run 保持 QUEUED
        assertThat(store.leaseNext()).isEmpty();
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?",
                String.class, childTwo)).isEqualTo("QUEUED");

        store.completeRun(firstChild, Map.of("summary", "done"), 0);
        assertThat(store.leaseNext().orElseThrow().id()).isEqualTo(childTwo);
    }

    @Test
    void quotaForReturnsDesignDefaultsWhenNoRowConfigured() {
        TenantQuotaService.TenantQuota quota = quotaService.quotaFor(AgentTestDatabase.TENANT_ID);
        assertThat(quota.maxConcurrentRuns()).isEqualTo(8);
        assertThat(quota.maxConcurrentSubagents()).isEqualTo(4);
        assertThat(quota.runWaitTimeoutSeconds()).isEqualTo(86_400);
    }
}

package com.superskidder.kylinops.backend.agentruntime.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 执行记忆链三级流转测试：记录 → 候选（确定性提炼）→ 事实（仅带 verifiedBy 提升）。
 * 红线断言：普通成功 run 只产生候选，绝不自动产生 verified_fact。
 */
class MemoryChainTest {

    private static final String TOOL_ID = "kylin.service.status";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private MemoryStore store;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        jdbc = MemoryTestDatabase.create();
        store = new MemoryStore(jdbc);
        service = new MemoryService(store, objectMapper);
    }

    private String insertRun() {
        String runId = "run-" + UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into agent_run (
                    id, tenant_id, human_principal_id, objective, execution_mode,
                    target_scope_json, target_scope_digest, grant_version, grant_snapshot_json,
                    status, input_revision, budget_json, usage_json, lease_owner, created_at, updated_at
                ) values (?, ?, ?, 'memory fixture', 'CAUTIOUS', ?, ?, 1, ?, ?, 0, ?, ?, ?, ?, ?)
                """, runId, MemoryTestDatabase.TENANT_ID, MemoryTestDatabase.USER_ID,
                "{\"nodeId\":\"node-1\"}", "digest-target", "{}", "RUNNING",
                "{}", "{}", "worker-test", now, now);
        return runId;
    }

    private LeasedAgentRun leased(String runId) {
        return new LeasedAgentRun(runId, MemoryTestDatabase.TENANT_ID, MemoryTestDatabase.USER_ID,
                "agent-root", "memory fixture", "CAUTIOUS",
                "{\"nodeId\":\"node-1\"}", "{}", "{}", 0L, null, "OFF");
    }

    private long candidateCount() {
        Long count = jdbc.queryForObject("select count(*) from memory_candidate", Long.class);
        return count == null ? -1 : count;
    }

    private long factCount() {
        return store.countVerifiedFacts(MemoryTestDatabase.TENANT_ID);
    }

    @Test
    void toolExecutionsProduceRecordsWithCorrectSuccessFlag() {
        LeasedAgentRun run = leased(insertRun());

        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        service.recordToolExecution(run, "kylin.service.stop", false, "unit not loaded");

        List<ExecutionRecord> records = store.executionRecordsByRun(run.tenantId(), run.id());
        assertThat(records).hasSize(2);
        assertThat(records).extracting(ExecutionRecord::success).containsExactly(true, false);
        assertThat(records).extracting(ExecutionRecord::toolId)
                .containsExactly(TOOL_ID, "kylin.service.stop");
        assertThat(records).allSatisfy(record -> {
            assertThat(record.tenantId()).isEqualTo(MemoryTestDatabase.TENANT_ID);
            assertThat(record.agentInstanceId()).isEqualTo("agent-root");
            assertThat(record.outcomeSummary()).isNotBlank();
        });
        assertThat(factCount()).isZero();
    }

    @Test
    void threeConsecutiveSuccessesExtractPendingCandidate() {
        LeasedAgentRun run = leased(insertRun());
        for (int i = 0; i < 3; i++) {
            service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        }

        List<MemoryCandidate> candidates = store.listCandidates(run.tenantId(), MemoryCandidate.STATUS_PENDING);
        assertThat(candidates).hasSize(1);
        MemoryCandidate candidate = candidates.get(0);
        assertThat(candidate.statement()).contains(TOOL_ID).contains("3");
        assertThat(candidate.sourceRecordId()).isNotBlank();
        assertThat(candidate.evidenceJson()).contains("consecutive_success");
        assertThat(factCount()).isZero();
    }

    @Test
    void brokenOrShortStreakExtractsNoCandidate() {
        LeasedAgentRun run = leased(insertRun());
        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        assertThat(candidateCount()).isZero();

        service.recordToolExecution(run, TOOL_ID, false, "timeout");
        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        assertThat(candidateCount()).isZero();
        assertThat(factCount()).isZero();
    }

    @Test
    void fourthSuccessDoesNotDuplicateCandidate() {
        LeasedAgentRun run = leased(insertRun());
        for (int i = 0; i < 4; i++) {
            service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        }
        assertThat(candidateCount()).isEqualTo(1);
    }

    @Test
    void successfulRunExtractsCandidateButNeverVerifiedFact() {
        LeasedAgentRun run = leased(insertRun());
        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        service.recordToolExecution(run, "kylin.workspace.read", true, "SUCCEEDED");

        service.recordRunCompleted(run);

        List<MemoryCandidate> candidates = store.listCandidates(run.tenantId(), MemoryCandidate.STATUS_PENDING);
        assertThat(candidates).hasSize(1);
        assertThat(candidates).extracting(MemoryCandidate::statement)
                .anySatisfy(statement -> assertThat(statement).contains(run.id()).contains("成功完成"));
        assertThat(candidates).extracting(MemoryCandidate::evidenceJson)
                .anySatisfy(evidence -> assertThat(evidence).contains("run_success"));
        // 红线：普通 COMPLETED run 不得自动产生 verified_fact
        assertThat(factCount()).isZero();
    }

    @Test
    void runWithFailuresOrWithoutRecordsExtractsNoRunCandidate() {
        LeasedAgentRun emptyRun = leased(insertRun());
        service.recordRunCompleted(emptyRun);

        LeasedAgentRun failedRun = leased(insertRun());
        service.recordToolExecution(failedRun, TOOL_ID, true, "SUCCEEDED");
        service.recordToolExecution(failedRun, TOOL_ID, false, "boom");
        service.recordRunCompleted(failedRun);

        assertThat(candidateCount()).isZero();
        assertThat(factCount()).isZero();
    }

    @Test
    void promoteWithoutVerifiedByIsRejected() {
        LeasedAgentRun run = leased(insertRun());
        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        service.recordRunCompleted(run);
        String candidateId = store.listCandidates(run.tenantId(), MemoryCandidate.STATUS_PENDING)
                .get(0).id();

        assertThatThrownBy(() -> service.promoteCandidate(run.tenantId(), candidateId, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.promoteCandidate(run.tenantId(), candidateId, "  "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(factCount()).isZero();
        assertThat(store.findCandidate(run.tenantId(), candidateId).orElseThrow().status())
                .isEqualTo(MemoryCandidate.STATUS_PENDING);
    }

    @Test
    void promoteUnknownCandidateIsRejected() {
        assertThatThrownBy(() -> service.promoteCandidate(MemoryTestDatabase.TENANT_ID, "memc-missing", "user-alpha"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(factCount()).isZero();
    }

    @Test
    void legalPromotionCreatesFactAndMarksCandidatePromoted() {
        LeasedAgentRun run = leased(insertRun());
        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        service.recordRunCompleted(run);
        MemoryCandidate candidate = store.listCandidates(run.tenantId(), MemoryCandidate.STATUS_PENDING).get(0);

        VerifiedFact fact = service.promoteCandidate(run.tenantId(), candidate.id(), "user-alpha");

        assertThat(fact.statement()).isEqualTo(candidate.statement());
        assertThat(fact.verifiedBy()).isEqualTo("user-alpha");
        assertThat(fact.verificationDigest()).matches("[0-9a-f]{64}");
        assertThat(store.findCandidate(run.tenantId(), candidate.id()).orElseThrow().status())
                .isEqualTo(MemoryCandidate.STATUS_PROMOTED);
        assertThat(service.listVerifiedFacts(run.tenantId(), 10))
                .extracting(VerifiedFact::id).containsExactly(fact.id());
    }

    @Test
    void repeatedPromotionIsRejected() {
        LeasedAgentRun run = leased(insertRun());
        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        service.recordRunCompleted(run);
        String candidateId = store.listCandidates(run.tenantId(), MemoryCandidate.STATUS_PENDING).get(0).id();

        service.promoteCandidate(run.tenantId(), candidateId, "user-alpha");
        assertThatThrownBy(() -> service.promoteCandidate(run.tenantId(), candidateId, "user-alpha"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(factCount()).isEqualTo(1);
    }

    @Test
    void rejectedCandidateCannotBePromoted() {
        LeasedAgentRun run = leased(insertRun());
        service.recordToolExecution(run, TOOL_ID, true, "SUCCEEDED");
        service.recordRunCompleted(run);
        String candidateId = store.listCandidates(run.tenantId(), MemoryCandidate.STATUS_PENDING).get(0).id();

        MemoryCandidate rejected = service.rejectCandidate(run.tenantId(), candidateId);
        assertThat(rejected.status()).isEqualTo(MemoryCandidate.STATUS_REJECTED);
        assertThatThrownBy(() -> service.promoteCandidate(run.tenantId(), candidateId, "user-alpha"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(factCount()).isZero();
    }
}

package com.superskidder.kylinops.backend.agentruntime.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 执行记忆链：ExecutionRecord → MemoryCandidate → VerifiedFact。
 * 候选由确定性规则从执行记录提炼（不依赖 LLM）；
 * 已验证事实只能经 {@link #promoteCandidate} 携带 verifiedBy 提升产生，
 * 普通 COMPLETED run 不会自动写入 verified_fact。
 */
@Service
public class MemoryService {

    public static final int CONSECUTIVE_SUCCESS_THRESHOLD = 3;
    private static final int SUMMARY_MAX_LENGTH = 500;

    private final MemoryStore store;
    private final ObjectMapper objectMapper;

    public MemoryService(MemoryStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /** 工具执行终态挂钩：落执行记录；成功时评估连续成功提炼规则。 */
    public ExecutionRecord recordToolExecution(
            LeasedAgentRun run, String toolId, boolean success, String outcomeSummary) {
        ExecutionRecord record = new ExecutionRecord(
                newId("exec"), run.tenantId(), run.id(), run.rootAgentInstanceId(),
                toolId, truncate(outcomeSummary), success, Instant.now());
        store.insertExecutionRecord(record);
        if (success) {
            maybeExtractStreakCandidate(run.tenantId(), toolId);
        }
        return record;
    }

    /** run 成功结束挂钩：全部执行记录成功时提炼结论模板候选；绝不写 verified_fact。 */
    public void recordRunCompleted(LeasedAgentRun run) {
        List<ExecutionRecord> records = store.executionRecordsByRun(run.tenantId(), run.id());
        if (records.isEmpty() || records.stream().anyMatch(record -> !record.success())) {
            return;
        }
        List<String> toolIds = records.stream().map(ExecutionRecord::toolId).distinct().toList();
        String statement = "Run %s 成功完成：%s 次工具执行全部成功（%s）"
                .formatted(run.id(), records.size(), String.join(", ", toolIds));
        if (store.candidateStatementExists(run.tenantId(), statement)) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rule", "run_success");
        evidence.put("runId", run.id());
        evidence.put("toolIds", toolIds);
        evidence.put("recordIds", records.stream().map(ExecutionRecord::id).toList());
        store.insertCandidate(new MemoryCandidate(newId("memc"), run.tenantId(),
                records.get(records.size() - 1).id(), statement, writeJson(evidence),
                MemoryCandidate.STATUS_PENDING, Instant.now()));
    }

    private void maybeExtractStreakCandidate(String tenantId, String toolId) {
        List<ExecutionRecord> latest =
                store.latestExecutionRecords(tenantId, toolId, CONSECUTIVE_SUCCESS_THRESHOLD);
        if (latest.size() < CONSECUTIVE_SUCCESS_THRESHOLD
                || latest.stream().anyMatch(record -> !record.success())) {
            return;
        }
        String statement = "工具 %s 已连续成功执行 %s 次".formatted(toolId, CONSECUTIVE_SUCCESS_THRESHOLD);
        if (store.candidateStatementExists(tenantId, statement)) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rule", "consecutive_success");
        evidence.put("toolId", toolId);
        evidence.put("count", CONSECUTIVE_SUCCESS_THRESHOLD);
        evidence.put("recordIds", latest.stream().map(ExecutionRecord::id).toList());
        store.insertCandidate(new MemoryCandidate(newId("memc"), tenantId,
                latest.get(0).id(), statement, writeJson(evidence),
                MemoryCandidate.STATUS_PENDING, Instant.now()));
    }

    /**
     * 提升候选为已验证事实（人工确认或二次执行验证入口）。
     * verifiedBy 为空、候选不存在或非 PENDING 一律拒绝。
     */
    public VerifiedFact promoteCandidate(String tenantId, String candidateId, String verifiedBy) {
        if (verifiedBy == null || verifiedBy.isBlank()) {
            throw new IllegalArgumentException("verifiedBy is required to promote a memory candidate");
        }
        MemoryCandidate candidate = store.findCandidate(tenantId, candidateId)
                .orElseThrow(() -> new IllegalArgumentException("memory candidate not found: " + candidateId));
        if (!MemoryCandidate.STATUS_PENDING.equals(candidate.status())) {
            throw new IllegalStateException(
                    "memory candidate is not PENDING and cannot be promoted: " + candidateId);
        }
        int updated = store.transitionCandidate(tenantId, candidateId,
                MemoryCandidate.STATUS_PENDING, MemoryCandidate.STATUS_PROMOTED);
        if (updated == 0) {
            throw new IllegalStateException(
                    "memory candidate was already promoted or rejected: " + candidateId);
        }
        String digest = Sm3.hex(candidate.id() + "|" + candidate.statement() + "|" + verifiedBy);
        VerifiedFact fact = new VerifiedFact(newId("fact"), tenantId, candidate.statement(),
                verifiedBy.trim(), digest, Instant.now());
        store.insertVerifiedFact(fact);
        return fact;
    }

    public MemoryCandidate rejectCandidate(String tenantId, String candidateId) {
        MemoryCandidate candidate = store.findCandidate(tenantId, candidateId)
                .orElseThrow(() -> new IllegalArgumentException("memory candidate not found: " + candidateId));
        if (!MemoryCandidate.STATUS_PENDING.equals(candidate.status())) {
            throw new IllegalStateException(
                    "memory candidate is not PENDING and cannot be rejected: " + candidateId);
        }
        int updated = store.transitionCandidate(tenantId, candidateId,
                MemoryCandidate.STATUS_PENDING, MemoryCandidate.STATUS_REJECTED);
        if (updated == 0) {
            throw new IllegalStateException("memory candidate status changed concurrently: " + candidateId);
        }
        return new MemoryCandidate(candidate.id(), candidate.tenantId(), candidate.sourceRecordId(),
                candidate.statement(), candidate.evidenceJson(),
                MemoryCandidate.STATUS_REJECTED, candidate.createdAt());
    }

    public List<MemoryCandidate> listPendingCandidates(String tenantId) {
        return store.listCandidates(tenantId, MemoryCandidate.STATUS_PENDING);
    }

    public List<VerifiedFact> listVerifiedFacts(String tenantId, int limit) {
        return store.listVerifiedFacts(tenantId, limit <= 0 ? 50 : limit);
    }

    private String truncate(String summary) {
        String value = summary == null ? "" : summary.strip();
        return value.length() <= SUMMARY_MAX_LENGTH ? value : value.substring(0, SUMMARY_MAX_LENGTH);
    }

    private String writeJson(Map<String, Object> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("memory evidence cannot be serialized", exception);
        }
    }

    private String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

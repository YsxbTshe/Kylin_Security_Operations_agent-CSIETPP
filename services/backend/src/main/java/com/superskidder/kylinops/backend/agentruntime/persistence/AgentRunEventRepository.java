package com.superskidder.kylinops.backend.agentruntime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.security.CanonicalJson;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * agent_run_event 聚合：追加、按序号回放、SM3 哈希链校验。
 * 从 {@link AgentRuntimeStore} 拆出，避免运行时仓储同时承担事件溯源职责。
 */
@Repository
public class AgentRunEventRepository {

    static final String ZERO_HASH = "0".repeat(64);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AgentRunEventNotifier eventNotifier;

    public AgentRunEventRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AgentRunEventNotifier eventNotifier
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventNotifier = eventNotifier;
    }

    public List<AgentRunEvent> eventsAfter(String tenantId, String runId, long afterSequence, int limit) {
        return jdbcTemplate.query("""
                select event_id, run_id, sequence_no, event_type, actor_type, actor_id, payload_json,
                       prev_hash, event_hash, created_at
                from agent_run_event where tenant_id = ? and run_id = ? and sequence_no > ?
                order by sequence_no asc limit ?
                """, (rs, rowNum) -> new AgentRunEvent(rs.getString("event_id"), rs.getString("run_id"),
                rs.getLong("sequence_no"), rs.getString("event_type"), rs.getString("actor_type"),
                rs.getString("actor_id"), rs.getString("payload_json"), rs.getString("prev_hash"),
                rs.getString("event_hash"), rs.getTimestamp("created_at").toInstant()),
                tenantId, runId, Math.max(0, afterSequence), Math.max(1, Math.min(limit, 1_000)));
    }

    /**
     * 校验一条 run 的 SM3 哈希链：genesis 必须为 ZERO_HASH，每条
     * event_hash = SM3(prev_hash + 规范化载荷)，且 prev_hash 等于前一条 event_hash。
     */
    public ChainVerification verifyChain(String tenantId, String runId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select event_id, run_id, tenant_id, sequence_no, event_type, actor_type, actor_id,
                       payload_json, prev_hash, event_hash, created_at
                from agent_run_event where tenant_id = ? and run_id = ?
                order by sequence_no asc
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventId", rs.getString("event_id"));
            row.put("runId", rs.getString("run_id"));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("sequence", rs.getLong("sequence_no"));
            row.put("eventType", rs.getString("event_type"));
            row.put("actorType", rs.getString("actor_type"));
            row.put("actorId", rs.getString("actor_id") == null ? "" : rs.getString("actor_id"));
            row.put("payloadJson", rs.getString("payload_json"));
            row.put("prevHash", rs.getString("prev_hash"));
            row.put("eventHash", rs.getString("event_hash"));
            Instant createdAt = rs.getTimestamp("created_at").toInstant().truncatedTo(ChronoUnit.MILLIS);
            row.put("createdAt", createdAt);
            return row;
        }, tenantId, runId);
        if (rows.isEmpty()) {
            return new ChainVerification(true, 0, null, "empty");
        }
        String expectedPrev = ZERO_HASH;
        for (Map<String, Object> row : rows) {
            String prevHash = String.valueOf(row.get("prevHash"));
            if (!expectedPrev.equals(prevHash)) {
                return new ChainVerification(false, ((Number) row.get("sequence")).longValue(),
                        String.valueOf(row.get("eventId")), "prev_hash mismatch");
            }
            String hashMaterial = canonical(Map.of(
                    "eventId", row.get("eventId"), "runId", row.get("runId"), "tenantId", row.get("tenantId"),
                    "sequence", row.get("sequence"), "eventType", row.get("eventType"),
                    "actorType", row.get("actorType"), "actorId", row.get("actorId"),
                    "payload", readJson(String.valueOf(row.get("payloadJson"))),
                    "createdAt", ((Instant) row.get("createdAt")).toString()
            ));
            String expectedHash = Sm3.hex((prevHash + hashMaterial).getBytes(StandardCharsets.UTF_8));
            if (!expectedHash.equals(row.get("eventHash"))) {
                return new ChainVerification(false, ((Number) row.get("sequence")).longValue(),
                        String.valueOf(row.get("eventId")), "event_hash mismatch");
            }
            expectedPrev = expectedHash;
        }
        return new ChainVerification(true, rows.size(), null, "ok");
    }

    @Transactional
    public AgentRunEvent appendEvent(String runId, String tenantId, String eventType, String actorType,
                                     String actorId, Map<String, Object> payload) {
        jdbcTemplate.queryForObject("select id from agent_run where id = ? and tenant_id = ? for update",
                String.class, runId, tenantId);
        List<PreviousEvent> previousRows = jdbcTemplate.query("""
                select sequence_no, event_hash from agent_run_event where run_id = ? order by sequence_no desc limit 1
                """, (rs, rowNum) -> new PreviousEvent(rs.getLong("sequence_no"), rs.getString("event_hash")), runId);
        long sequence = previousRows.isEmpty() ? 1 : previousRows.get(0).sequence() + 1;
        String previousHash = previousRows.isEmpty() ? ZERO_HASH : previousRows.get(0).hash();
        String eventId = "evt-" + UUID.randomUUID().toString().replace("-", "");
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String payloadJson = canonical(payload);
        String hashMaterial = canonical(Map.of(
                "eventId", eventId, "runId", runId, "tenantId", tenantId, "sequence", sequence,
                "eventType", eventType, "actorType", actorType, "actorId", actorId == null ? "" : actorId,
                "payload", readJson(payloadJson), "createdAt", createdAt.toString()
        ));
        String eventHash = Sm3.hex((previousHash + hashMaterial).getBytes(StandardCharsets.UTF_8));
        jdbcTemplate.update("""
                insert into agent_run_event (
                    event_id, run_id, tenant_id, sequence_no, event_type, actor_type, actor_id,
                    payload_json, prev_hash, event_hash, hash_algorithm, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SM3', ?)
                """, eventId, runId, tenantId, sequence, eventType, actorType, blankToNull(actorId), payloadJson,
                previousHash, eventHash, Timestamp.from(createdAt));
        // 这里曾经再往 outbox_event 写一份带完整正文的 PENDING 行。它的唯一消费者
        // （Redis Stream 发布器）已随 Phase 7 重构删除，于是那张表只增不减、且绕过
        // RuntimeRetentionService 的保留期清理（保留策略只清 agent_run_event）。
        // 审计权威源是本表的 SM3 哈希链，outbox 从不参与校验，故随 V9 迁移一并下线。
        // 将来要接消息总线时，请连同消费者与投递状态机一起加回，不要只留一张永远 PENDING 的表。
        AgentRunEvent event = new AgentRunEvent(eventId, runId, sequence, eventType, actorType, actorId,
                payloadJson, previousHash, eventHash, createdAt);
        afterCommit(() -> eventNotifier.publish(event));
        return event;
    }

    public record ChainVerification(boolean valid, long eventCount, String brokenEventId, String reason) {}

    private void afterCommit(Runnable runnable) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { runnable.run(); }
            });
        } else {
            runnable.run();
        }
    }

    private String canonical(Object value) {
        return CanonicalJson.canonicalize(json(value));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value cannot be serialized as JSON", exception);
        }
    }

    private Object readJson(String value) {
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid JSON", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record PreviousEvent(long sequence, String hash) {}
}

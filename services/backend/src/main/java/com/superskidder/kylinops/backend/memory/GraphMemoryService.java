package com.superskidder.kylinops.backend.memory;

import com.superskidder.kylinops.backend.rag.KnowledgeGraphService;
import com.superskidder.kylinops.backend.rag.RagEmbeddingService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 图增强的 Agent 记忆（ADR-001 §2.7：记忆是只追加的历史记录）。
 *
 * 记忆被嵌入、关联到知识图谱实体，并由「语义相似 + 图实体重合 + 时近衰减 + 重要度 +
 * 任务相似度」五路复合召回；召回时再按出处（来源任务结局、是否验证、有效期、是否被取代）
 * 计算可信度对分数加权。
 *
 * 写入是**只追加**的：不再就地合并近重复项，而是插入新记忆并用 supersedes_id 指向被它
 * 取代的旧记忆——旧的保留为历史，召回时降权而非删除。同一 scope 下存在两条互不取代且
 * 内容分歧的记忆时，二者并列呈现并标记为冲突，由使用处裁断，而不是在写入时替某一方做主。
 */
@Service
public class GraphMemoryService {

    private static final double SUPERSEDE_THRESHOLD = 0.93D;
    private static final int ENTITY_LINKS_PER_MEMORY = 6;
    private static final int QUERY_SEED_NODES = 8;

    private final JdbcTemplate jdbcTemplate;
    private final RagEmbeddingService embeddingService;
    private final KnowledgeGraphService knowledgeGraphService;

    public GraphMemoryService(JdbcTemplate jdbcTemplate, RagEmbeddingService embeddingService,
                              KnowledgeGraphService knowledgeGraphService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    // ---------------------------------------------------------------------
    // Write path: append-only + supersedes + entity linking
    // ---------------------------------------------------------------------

    /** 向后兼容的简单入口：无出处/生命周期信息，等价于一条未验证、无来源任务的记忆。 */
    public String remember(String organizationId, String memoryType, String title, String content,
                           int importance, String memorySource, String eventRef) {
        return remember(organizationId, new MemoryWrite(memoryType, title, content, importance,
                memorySource, eventRef, null, null, false, null, null));
    }

    /**
     * 只追加地写入一条记忆。若已存在高度相似且同 scope 的记忆，则本条**取代**它
     * （supersedes_id 指向旧记忆，旧的保留为历史），而不是就地合并。
     */
    public String remember(String organizationId, MemoryWrite write) {
        String text = write.title() + "\n" + write.content();
        List<Double> embedding = embeddingService.embed(text);
        Set<String> terms = tokenize(text);
        LocalDateTime now = LocalDateTime.now();

        String supersedesId = findSupersededTarget(organizationId, embedding, write.scope());
        return insertMemory(organizationId, write, embedding, terms, now, supersedesId);
    }

    /** Add a memory without supersede detection, at an explicit creation time. Used for controlled ingestion/benchmark. */
    public String add(String organizationId, String memoryType, String title, String content, int importance,
                      String memorySource, String eventRef, LocalDateTime createdAt) {
        return add(organizationId, new MemoryWrite(memoryType, title, content, importance, memorySource,
                eventRef, null, null, false, null, null), createdAt);
    }

    /** Add a memory (with full provenance) at an explicit creation time, without supersede detection. */
    public String add(String organizationId, MemoryWrite write, LocalDateTime createdAt) {
        List<Double> embedding = embeddingService.embed(write.title() + "\n" + write.content());
        Set<String> terms = tokenize(write.title() + "\n" + write.content());
        return insertMemory(organizationId, write, embedding, terms, createdAt, null);
    }

    private String insertMemory(String organizationId, MemoryWrite write, List<Double> embedding,
                                Set<String> terms, LocalDateTime createdAt, String supersedesId) {
        String id = "mem-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("""
                insert into app_rag_memory (id, organization_id, memory_type, title, content, importance,
                    source_document_id, embedding_json, memory_source, last_accessed_at, access_count,
                    event_ref, valid, created_at, updated_at,
                    source_task_id, task_outcome, supersedes_id, verified, valid_until, scope)
                values (?, ?, ?, ?, ?, ?, null, ?, ?, ?, 0, ?, true, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, write.memoryType().toUpperCase(Locale.ROOT), write.title(),
                write.content(), write.importance(), embeddingService.toJson(embedding),
                write.memorySource() == null ? "MANUAL" : write.memorySource(),
                Timestamp.valueOf(createdAt), write.eventRef(), Timestamp.valueOf(createdAt),
                Timestamp.valueOf(createdAt), write.sourceTaskId(), write.taskOutcome(), supersedesId,
                write.verified(), write.validUntil() == null ? null : Timestamp.valueOf(write.validUntil()),
                write.scope());
        linkEntities(organizationId, id, terms, embedding);
        return id;
    }

    private void linkEntities(String organizationId, String memoryId, Set<String> terms, List<Double> embedding) {
        List<String> nodeIds = knowledgeGraphService.matchNodeIds(organizationId, terms, embedding, ENTITY_LINKS_PER_MEMORY);
        jdbcTemplate.update("delete from app_memory_entity_link where memory_id = ?", memoryId);
        for (String nodeId : nodeIds) {
            jdbcTemplate.update("""
                    insert into app_memory_entity_link (organization_id, memory_id, node_id, weight)
                    values (?, ?, ?, 1.0)
                    """, organizationId, memoryId, nodeId);
        }
    }

    /**
     * 找出本条记忆应当取代的旧记忆：高度相似（近重复），且 scope 一致（或双方均未声明 scope）。
     * 只在这种「同一主体的更新」情形下建立取代关系；否则返回 null，作为一条独立的新记忆追加。
     */
    private String findSupersededTarget(String organizationId, List<Double> embedding, String scope) {
        List<MemRow> rows = loadValidMemories(organizationId);
        Set<String> supersededIds = loadSupersededIds(organizationId);
        String bestId = null;
        double bestCos = -1.0D;
        for (MemRow row : rows) {
            if (supersededIds.contains(row.id())) {
                continue; // 已被取代的旧记忆不再作为取代目标
            }
            if (!sameScope(scope, row.scope())) {
                continue;
            }
            double cos = embeddingService.cosine(embedding, row.embedding());
            if (cos > bestCos) {
                bestCos = cos;
                bestId = row.id();
            }
        }
        return bestCos >= SUPERSEDE_THRESHOLD ? bestId : null;
    }

    private boolean sameScope(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        return x.equalsIgnoreCase(y);
    }

    // ---------------------------------------------------------------------
    // Recall: composite (5-channel) + provenance-based trust + conflict surfacing
    // ---------------------------------------------------------------------

    public List<MemoryHit> recall(String organizationId, String query, int k, MemoryRetrievalConfig config) {
        List<Double> queryEmbedding = embeddingService.embed(query);
        Set<String> terms = tokenize(query);

        Set<String> neighborhood = new HashSet<>();
        if (config.useGraph()) {
            List<String> seeds = knowledgeGraphService.matchNodeIds(organizationId, terms, queryEmbedding, QUERY_SEED_NODES);
            neighborhood.addAll(seeds);
            neighborhood.addAll(knowledgeGraphService.neighborhoodNodeIds(organizationId, seeds, config.graphHops()));
        }

        List<MemRow> memories = loadValidMemories(organizationId);
        Map<String, Set<String>> linksByMemory = loadLinks(organizationId);
        Set<String> supersededIds = loadSupersededIds(organizationId);
        Map<String, Long> scopeCounts = latestPerScopeCounts(memories, supersededIds);
        LocalDateTime now = LocalDateTime.now();

        List<MemoryHit> hits = new ArrayList<>();
        for (MemRow memory : memories) {
            double semantic = config.useSemantic()
                    ? Math.max(0.0D, embeddingService.cosine(queryEmbedding, memory.embedding())) : 0.0D;
            double recency = 0.0D;
            if (config.useRecency()) {
                double ageHours = Math.max(0.0D, Duration.between(memory.createdAt(), now).toMinutes() / 60.0D);
                recency = Math.exp(-config.recencyLambdaPerHour() * ageHours);
            }
            double importance = config.useImportance() ? Math.min(1.0D, memory.importance() / 5.0D) : 0.0D;
            double graph = 0.0D;
            if (config.useGraph() && !neighborhood.isEmpty()) {
                Set<String> links = linksByMemory.getOrDefault(memory.id(), Set.of());
                if (!links.isEmpty()) {
                    long overlap = links.stream().filter(neighborhood::contains).count();
                    graph = (double) overlap / (double) links.size();
                }
            }
            // 第五路：任务相似度——执行相似工作时找到既往任务记录（仅对任务派生记忆计分）。
            double taskAffinity = 0.0D;
            if (config.useTaskAffinity() && isTaskDerived(memory)) {
                taskAffinity = Math.max(0.0D, embeddingService.cosine(queryEmbedding, memory.embedding()));
            }

            double relevance = config.weightSemantic() * semantic + config.weightGraph() * graph
                    + config.weightRecency() * recency + config.weightImportance() * importance
                    + config.weightTaskAffinity() * taskAffinity;

            boolean superseded = supersededIds.contains(memory.id());
            boolean expired = memory.validUntil() != null && memory.validUntil().isBefore(now);
            boolean conflicting = memory.scope() != null && !memory.scope().isBlank()
                    && !superseded && scopeCounts.getOrDefault(scopeKey(memory.scope()), 0L) > 1;
            double trust = config.useTrust()
                    ? trustMultiplier(memory, superseded, expired) : 1.0D;

            double score = relevance * trust;
            hits.add(new MemoryHit(memory.id(), memory.memoryType(), memory.memorySource(), memory.title(),
                    memory.content(), memory.importance(), score, semantic, graph, recency, taskAffinity,
                    trust, memory.verified(), superseded, expired, conflicting, memory.scope()));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<MemoryHit> top = hits.stream().limit(Math.max(1, k)).toList();
        reinforce(top, now);
        return top;
    }

    private double trustMultiplier(MemRow memory, boolean superseded, boolean expired) {
        return MemoryTrust.multiplier(memory.verified(), memory.taskOutcome(), superseded, expired);
    }

    private boolean isTaskDerived(MemRow memory) {
        return "OP_LOG".equalsIgnoreCase(memory.memoryType())
                || (memory.sourceTaskId() != null && !memory.sourceTaskId().isBlank());
    }

    private Map<String, Long> latestPerScopeCounts(List<MemRow> memories, Set<String> supersededIds) {
        Map<String, Long> counts = new HashMap<>();
        for (MemRow memory : memories) {
            if (memory.scope() == null || memory.scope().isBlank() || supersededIds.contains(memory.id())) {
                continue;
            }
            counts.merge(scopeKey(memory.scope()), 1L, Long::sum);
        }
        return counts;
    }

    private String scopeKey(String scope) {
        return scope.trim().toLowerCase(Locale.ROOT);
    }

    private void reinforce(List<MemoryHit> hits, LocalDateTime now) {
        for (MemoryHit hit : hits) {
            jdbcTemplate.update(
                    "update app_rag_memory set access_count = access_count + 1, last_accessed_at = ? where id = ?",
                    Timestamp.valueOf(now), hit.id());
        }
    }

    // ---------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------

    private List<MemRow> loadValidMemories(String organizationId) {
        // Recency uses created_at (memory freshness) so newer knowledge supersedes older contradictory
        // facts; access reinforcement is tracked separately and does not reset freshness.
        return jdbcTemplate.query("""
                select id, memory_type, memory_source, title, content, importance, embedding_json, created_at,
                    source_task_id, task_outcome, supersedes_id, verified, valid_until, scope
                from app_rag_memory where organization_id = ? and valid = true
                """, (rs, n) -> new MemRow(rs.getString("id"), rs.getString("memory_type"),
                rs.getString("memory_source"), rs.getString("title"), rs.getString("content"),
                rs.getInt("importance"), embeddingService.fromJson(rs.getString("embedding_json")),
                rs.getTimestamp("created_at") == null
                        ? LocalDateTime.now() : rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("source_task_id"), rs.getString("task_outcome"),
                rs.getBoolean("verified"),
                rs.getTimestamp("valid_until") == null ? null : rs.getTimestamp("valid_until").toLocalDateTime(),
                rs.getString("scope")),
                organizationId);
    }

    /** 被取代的记忆 id 集合：某条记忆若被另一条的 supersedes_id 指向，即视为已被取代。 */
    private Set<String> loadSupersededIds(String organizationId) {
        Set<String> ids = new HashSet<>();
        jdbcTemplate.query(
                "select supersedes_id from app_rag_memory where organization_id = ? and supersedes_id is not null",
                rs -> { ids.add(rs.getString("supersedes_id")); }, organizationId);
        return ids;
    }

    private Map<String, Set<String>> loadLinks(String organizationId) {
        Map<String, Set<String>> links = new HashMap<>();
        jdbcTemplate.query("select memory_id, node_id from app_memory_entity_link where organization_id = ?",
                rs -> { links.computeIfAbsent(rs.getString("memory_id"), key -> new HashSet<>())
                        .add(rs.getString("node_id")); }, organizationId);
        return links;
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream((text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fa5]+"))
                .filter(term -> term.length() >= 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private record MemRow(String id, String memoryType, String memorySource, String title, String content,
                          int importance, List<Double> embedding, LocalDateTime createdAt,
                          String sourceTaskId, String taskOutcome, boolean verified,
                          LocalDateTime validUntil, String scope) {
    }
}

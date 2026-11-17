package com.superskidder.kylinops.backend.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.superskidder.kylinops.backend.common.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.llm.ChatMessage;
import com.superskidder.kylinops.backend.llm.LlmCompletionService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds and queries the RAG knowledge graph: LLM entity/relation extraction on ingest,
 * typed/weighted node+edge storage, node<->chunk evidence links, and bounded k-hop
 * neighborhood retrieval used as the graph channel of hybrid search.
 * See docs/01-架构设计/Graph-RAG与混合检索设计.md.
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    // Controlled vocabularies keep the graph explainable/auditable and constrain LLM output.
    static final Set<String> NODE_TYPES = Set.of(
            "CONCEPT", "COMPONENT", "SERVICE", "COMMAND", "TOOL", "FILE_PATH",
            "CONFIG", "RISK_LEVEL", "POLICY", "PROCEDURE", "THREAT", "ACCOUNT");
    static final Set<String> RELATIONS = Set.of(
            "PART_OF", "DEPENDS_ON", "CONFIGURED_BY", "MANAGED_BY", "REQUIRES_APPROVAL",
            "MITIGATES", "DETECTS", "AFFECTS", "PROTECTS", "RELATED_TO");

    private static final int DEFAULT_HOPS = 2;
    private static final double DEFAULT_DECAY = 0.5D;
    private static final int DEFAULT_SEED_TOP_M = 8;
    private static final int MAX_CHUNKS_PER_EXTRACTION = 10;
    // This embedding/LLM endpoint throttles concurrent requests hard (a lone call ~5s, but 3 at
    // once ~95s each). Sequential is fastest here. Bump only if a higher-throughput endpoint is used.
    private static final int EXTRACTION_CONCURRENCY = 1;

    private final JdbcTemplate jdbcTemplate;
    private final LlmCompletionService llmCompletionService;
    private final RagEmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphService(
            JdbcTemplate jdbcTemplate,
            LlmCompletionService llmCompletionService,
            RagEmbeddingService embeddingService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmCompletionService = llmCompletionService;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------
    // Extraction / build
    // ---------------------------------------------------------------------

    /** Rebuild graph for one document from its already-persisted chunks. Skips silently if LLM disabled. */
    public void buildForDocument(String organizationId, String documentId) {
        if (!llmCompletionService.isEnabled()) {
            return;
        }
        clearDocumentGraph(organizationId, documentId);
        List<ChunkRef> chunks = jdbcTemplate.query("""
                select id, content from app_rag_chunk
                where organization_id = ? and document_id = ?
                order by chunk_index
                """, (rs, n) -> new ChunkRef(rs.getString("id"), rs.getString("content")),
                organizationId, documentId);
        if (chunks.size() > MAX_CHUNKS_PER_EXTRACTION) {
            chunks = chunks.subList(0, MAX_CHUNKS_PER_EXTRACTION);
        }
        if (chunks.isEmpty()) {
            return;
        }
        // The LLM call is the bottleneck; run extractions concurrently, then apply DB upserts
        // sequentially so node/edge dedup stays race-free.
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(EXTRACTION_CONCURRENCY, chunks.size()));
        List<Future<Extraction>> futures = new ArrayList<>();
        for (ChunkRef chunk : chunks) {
            futures.add(pool.submit(() -> extractRaw(chunk)));
        }
        pool.shutdown();
        for (Future<Extraction> future : futures) {
            Extraction extraction;
            try {
                extraction = future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException exception) {
                log.warn("KG extraction task failed: {}", exception.getMessage());
                continue;
            }
            if (extraction != null) {
                applyExtraction(organizationId, documentId, extraction);
            }
        }
        recomputeSalience(organizationId);
    }

    private Extraction extractRaw(ChunkRef chunk) {
        String json = llmCompletionService.completeJson(List.of(
                new ChatMessage("system", extractionSystemPrompt()),
                new ChatMessage("user", chunk.content())
        ));
        try {
            return new Extraction(chunk.id(), objectMapper.readTree(json));
        } catch (Exception exception) {
            log.warn("KG extraction returned non-JSON for chunk {}", chunk.id());
            return null;
        }
    }

    private void applyExtraction(String organizationId, String documentId, Extraction extraction) {
        String chunkId = extraction.chunkId();
        JsonNode root = extraction.root();
        Map<String, String> nameToId = new HashMap<>();
        for (JsonNode entity : root.path("entities")) {
            String name = entity.path("name").asText("").trim();
            String type = entity.path("type").asText("").trim().toUpperCase(Locale.ROOT);
            String description = entity.path("description").asText("").trim();
            if (name.isBlank() || !NODE_TYPES.contains(type)) {
                continue;
            }
            String nodeId = upsertNode(organizationId, name, type, description);
            nameToId.put(normalize(name), nodeId);
            linkNodeChunk(organizationId, nodeId, chunkId);
        }
        for (JsonNode relation : root.path("relations")) {
            String source = relation.path("source").asText("").trim();
            String target = relation.path("target").asText("").trim();
            String rel = relation.path("relation").asText("").trim().toUpperCase(Locale.ROOT);
            String evidence = relation.path("evidence").asText("").trim();
            if (source.isBlank() || target.isBlank()) {
                continue;
            }
            if (!RELATIONS.contains(rel)) {
                rel = "RELATED_TO";
            }
            // Relations may reference entities not in the entity list; upsert them as CONCEPT.
            String srcId = nameToId.computeIfAbsent(normalize(source),
                    key -> upsertNode(organizationId, source, "CONCEPT", ""));
            String dstId = nameToId.computeIfAbsent(normalize(target),
                    key -> upsertNode(organizationId, target, "CONCEPT", ""));
            if (srcId.equals(dstId)) {
                continue;
            }
            upsertEdge(organizationId, srcId, dstId, rel, evidence, documentId, chunkId);
        }
    }

    private record Extraction(String chunkId, JsonNode root) {
    }

    private String upsertNode(String organizationId, String name, String type, String description) {
        String normalized = normalize(name);
        List<String> existing = jdbcTemplate.query("""
                select id from app_kg_node
                where organization_id = ? and normalized_name = ? and node_type = ?
                """, (rs, n) -> rs.getString("id"), organizationId, normalized, type);
        LocalDateTime now = LocalDateTime.now();
        if (!existing.isEmpty()) {
            String id = existing.get(0);
            jdbcTemplate.update("""
                    update app_kg_node set mention_count = mention_count + 1, updated_at = ?,
                        description = case when (description is null or length(description) < length(?)) then ? else description end
                    where id = ?
                    """, Timestamp.valueOf(now), description, description, id);
            return id;
        }
        String id = "kgn-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String embedding = embeddingService.toJson(embeddingService.embed(name + "\n" + description));
        jdbcTemplate.update("""
                insert into app_kg_node (id, organization_id, name, normalized_name, node_type, description,
                    embedding_json, salience, mention_count, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 0, 1, ?, ?)
                """, id, organizationId, name, normalized, type, description, embedding,
                Timestamp.valueOf(now), Timestamp.valueOf(now));
        return id;
    }

    private void upsertEdge(String organizationId, String srcId, String dstId, String relation,
                            String evidence, String documentId, String chunkId) {
        List<String> existing = jdbcTemplate.query("""
                select id from app_kg_edge
                where organization_id = ? and src_node_id = ? and dst_node_id = ? and relation = ?
                """, (rs, n) -> rs.getString("id"), organizationId, srcId, dstId, relation);
        if (!existing.isEmpty()) {
            jdbcTemplate.update("update app_kg_edge set weight = weight + 0.5 where id = ?", existing.get(0));
            return;
        }
        String id = "kge-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("""
                insert into app_kg_edge (id, organization_id, src_node_id, dst_node_id, relation, weight,
                    evidence, source_document_id, source_chunk_id, created_at)
                values (?, ?, ?, ?, ?, 1.0, ?, ?, ?, ?)
                """, id, organizationId, srcId, dstId, relation,
                evidence.isBlank() ? null : evidence, documentId, chunkId,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    // ---------------------------------------------------------------------
    // 人工维护图谱（作用于 app_kg_edge，即 /rag/graph 实际展示的那张图）
    //
    // 注意与 app_rag_graph_edge 区分：后者是另一套手工关系表，端点为 /rag/graph-edges，
    // 两者 id 空间不通用，不能互相操作。
    // ---------------------------------------------------------------------

    /** 按节点名新建关系；节点不存在时按 CONCEPT 建出来，保证人工补的边能立刻在图上看到。 */
    public void createEdgeByName(String organizationId, String sourceName, String relation,
                                 String targetName, String evidence) {
        String source = sourceName == null ? "" : sourceName.trim();
        String target = targetName == null ? "" : targetName.trim();
        String rel = relation == null ? "" : relation.trim().toUpperCase(Locale.ROOT);
        if (source.isBlank() || target.isBlank() || rel.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "源节点、关系和目标节点都不能为空");
        }
        String srcId = resolveOrCreateNode(organizationId, source);
        String dstId = resolveOrCreateNode(organizationId, target);
        if (srcId.equals(dstId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "源节点和目标节点不能是同一个实体");
        }
        upsertEdge(organizationId, srcId, dstId, rel, evidence == null ? "" : evidence.trim(), null, null);
        recomputeSalience(organizationId);
    }

    /** 已存在同名实体（不限类型）时复用，避免人工补边时凭空造出重复节点。 */
    private String resolveOrCreateNode(String organizationId, String name) {
        List<String> existing = jdbcTemplate.query("""
                select id from app_kg_node
                where organization_id = ? and normalized_name = ?
                order by salience desc
                """, (rs, n) -> rs.getString("id"), organizationId, normalize(name));
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return upsertNode(organizationId, name, "CONCEPT", "");
    }

    /** 只允许改关系与证据；改端点等于换一条边，应新建后删除旧边。 */
    public void updateEdge(String organizationId, String edgeId, String relation, String evidence) {
        String rel = relation == null ? "" : relation.trim().toUpperCase(Locale.ROOT);
        if (rel.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "关系不能为空");
        }
        int updated = jdbcTemplate.update("""
                update app_kg_edge set relation = ?, evidence = ?
                where organization_id = ? and id = ?
                """, rel, evidence == null || evidence.isBlank() ? null : evidence.trim(), organizationId, edgeId);
        if (updated == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "图谱关系不存在");
        }
    }

    public void deleteEdge(String organizationId, String edgeId) {
        int deleted = jdbcTemplate.update(
                "delete from app_kg_edge where organization_id = ? and id = ?", organizationId, edgeId);
        if (deleted == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "图谱关系不存在");
        }
        recomputeSalience(organizationId);
    }

    /**
     * 某个实体的来源分片（证据回链），按提及次数降序。
     *
     * <p>走 app_kg_node_chunk 这张抽取时写入的绑定表，不做二次检索，
     * 因此返回的每一条都确实是该实体被抽取出来的出处。
     */
    public List<RagNodeChunkItem> nodeChunks(String organizationId, String nodeId, int limit) {
        return jdbcTemplate.query("""
                select c.id, c.document_id, c.chunk_index, c.title, c.source_type, c.content,
                       nc.weight, d.title as document_title
                from app_kg_node_chunk nc
                join app_rag_chunk c on c.id = nc.chunk_id
                left join app_rag_document d on d.id = c.document_id
                where nc.organization_id = ? and nc.node_id = ?
                order by nc.weight desc, c.chunk_index asc
                limit ?
                """, (rs, n) -> new RagNodeChunkItem(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getString("document_title"),
                rs.getInt("chunk_index"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getString("content"),
                rs.getDouble("weight")
        ), organizationId, nodeId, limit <= 0 ? 10 : limit);
    }

    private void linkNodeChunk(String organizationId, String nodeId, String chunkId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from app_kg_node_chunk where node_id = ? and chunk_id = ?",
                Integer.class, nodeId, chunkId);
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "update app_kg_node_chunk set weight = weight + 1 where node_id = ? and chunk_id = ?",
                    nodeId, chunkId);
            return;
        }
        jdbcTemplate.update("""
                insert into app_kg_node_chunk (organization_id, node_id, chunk_id, weight)
                values (?, ?, ?, 1.0)
                """, organizationId, nodeId, chunkId);
    }

    private void recomputeSalience(String organizationId) {
        // salience = degree / maxDegree, a cheap centrality proxy for ranking + node sizing.
        Map<String, Integer> degree = new HashMap<>();
        jdbcTemplate.query("select src_node_id, dst_node_id from app_kg_edge where organization_id = ?",
                rs -> {
                    degree.merge(rs.getString("src_node_id"), 1, Integer::sum);
                    degree.merge(rs.getString("dst_node_id"), 1, Integer::sum);
                }, organizationId);
        int max = degree.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        degree.forEach((nodeId, deg) ->
                jdbcTemplate.update("update app_kg_node set salience = ? where id = ?",
                        (double) deg / (double) max, nodeId));
    }

    private void clearDocumentGraph(String organizationId, String documentId) {
        // Remove this document's edges; orphan node cleanup happens on full rebuild.
        jdbcTemplate.update("delete from app_kg_edge where organization_id = ? and source_document_id = ?",
                organizationId, documentId);
    }

    // ---------------------------------------------------------------------
    // Graph channel retrieval: seed -> bounded BFS -> chunk scores
    // ---------------------------------------------------------------------

    /** Graph channel: seed match -> bounded k-hop BFS -> chunk scores + the reached subgraph node set. */
    public GraphRetrieval graphRetrieve(String organizationId, Set<String> terms, List<Double> queryEmbedding) {
        List<NodeRow> nodes = loadNodes(organizationId);
        if (nodes.isEmpty()) {
            return GraphRetrieval.empty();
        }
        List<String> seeds = seedNodes(nodes, terms, queryEmbedding);
        if (seeds.isEmpty()) {
            return GraphRetrieval.empty();
        }
        Map<String, Double> reached = bfsExpand(organizationId, seeds, DEFAULT_HOPS, DEFAULT_DECAY);
        Map<String, Double> chunkScores = chunkScores(organizationId, reached, nodes);
        return new GraphRetrieval(chunkScores, reached.keySet());
    }

    /** Match free text to the top-m most relevant KG node ids (name overlap + node-embedding cosine). */
    public List<String> matchNodeIds(String organizationId, Set<String> terms, List<Double> queryEmbedding, int topM) {
        List<NodeRow> nodes = loadNodes(organizationId);
        List<ScoredNode> scored = new ArrayList<>();
        for (NodeRow node : nodes) {
            double nameScore = nameMatchScore(node, terms);
            double vectorScore = node.embedding().isEmpty()
                    ? 0.0D : Math.max(0.0D, embeddingService.cosine(queryEmbedding, node.embedding()));
            double score = nameScore * 0.6D + vectorScore * 0.4D;
            if (score > 0.0D) {
                scored.add(new ScoredNode(node.id(), score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredNode::score).reversed());
        return scored.stream().limit(topM).map(ScoredNode::id).toList();
    }

    /** k-hop neighborhood (node id set) around the given seeds; used by graph-augmented memory recall. */
    public Set<String> neighborhoodNodeIds(String organizationId, java.util.Collection<String> seedIds, int hops) {
        if (seedIds == null || seedIds.isEmpty()) {
            return Set.of();
        }
        return bfsExpand(organizationId, new ArrayList<>(seedIds), Math.max(1, hops), DEFAULT_DECAY).keySet();
    }

    /** Edges whose endpoints are both within the given node set, rendered with node names for display/audit. */
    public List<RagGraphEdgeItem> edgesAsItems(String organizationId, Set<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> nameById = new HashMap<>();
        Map<String, String> docById = new HashMap<>();
        for (NodeRow node : loadNodes(organizationId)) {
            nameById.put(node.id(), node.name());
        }
        List<RagGraphEdgeItem> items = new ArrayList<>();
        jdbcTemplate.query("""
                select id, src_node_id, dst_node_id, relation, evidence, source_document_id
                from app_kg_edge where organization_id = ?
                """, rs -> {
            String src = rs.getString("src_node_id");
            String dst = rs.getString("dst_node_id");
            if (nodeIds.contains(src) && nodeIds.contains(dst)) {
                items.add(new RagGraphEdgeItem(rs.getString("id"),
                        nameById.getOrDefault(src, src), rs.getString("relation"),
                        nameById.getOrDefault(dst, dst), rs.getString("evidence"),
                        rs.getString("source_document_id")));
            }
        }, organizationId);
        return items;
    }

    public record GraphRetrieval(Map<String, Double> chunkScores, Set<String> reachedNodeIds) {
        static GraphRetrieval empty() {
            return new GraphRetrieval(Map.of(), Set.of());
        }
    }

    private List<String> seedNodes(List<NodeRow> nodes, Set<String> terms, List<Double> queryEmbedding) {
        List<ScoredNode> scored = new ArrayList<>();
        for (NodeRow node : nodes) {
            double nameScore = nameMatchScore(node, terms);
            double vectorScore = node.embedding().isEmpty()
                    ? 0.0D : Math.max(0.0D, embeddingService.cosine(queryEmbedding, node.embedding()));
            double score = nameScore * 0.6D + vectorScore * 0.4D;
            if (score > 0.0D) {
                scored.add(new ScoredNode(node.id(), score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredNode::score).reversed());
        return scored.stream().limit(DEFAULT_SEED_TOP_M).map(ScoredNode::id).toList();
    }

    private double nameMatchScore(NodeRow node, Set<String> terms) {
        if (terms.isEmpty()) {
            return 0.0D;
        }
        String haystack = node.normalizedName();
        int hits = 0;
        for (String term : terms) {
            if (haystack.contains(term) || term.contains(haystack)) {
                hits++;
            }
        }
        return (double) hits / (double) terms.size();
    }

    /** Undirected bounded breadth-first expansion; returns nodeId -> accumulated path weight. */
    private Map<String, Double> bfsExpand(String organizationId, List<String> seeds, int hops, double decay) {
        Map<String, List<Adj>> adjacency = loadAdjacency(organizationId);
        Map<String, Double> best = new HashMap<>();
        ArrayDeque<String[]> queue = new ArrayDeque<>(); // {nodeId, hop}
        Map<String, Double> weightOf = new HashMap<>();
        for (String seed : seeds) {
            best.put(seed, 1.0D);
            weightOf.put(seed, 1.0D);
            queue.add(new String[]{seed, "0"});
        }
        while (!queue.isEmpty()) {
            String[] current = queue.poll();
            String nodeId = current[0];
            int hop = Integer.parseInt(current[1]);
            if (hop >= hops) {
                continue;
            }
            double currentWeight = weightOf.getOrDefault(nodeId, 0.0D);
            for (Adj adj : adjacency.getOrDefault(nodeId, List.of())) {
                double next = currentWeight * adj.weight() * decay;
                if (next > best.getOrDefault(adj.target(), 0.0D)) {
                    best.put(adj.target(), next);
                    weightOf.put(adj.target(), next);
                    queue.add(new String[]{adj.target(), String.valueOf(hop + 1)});
                }
            }
        }
        return best;
    }

    private Map<String, Double> chunkScores(String organizationId, Map<String, Double> reachedNodes, List<NodeRow> nodes) {
        Map<String, Double> salienceById = new HashMap<>();
        for (NodeRow node : nodes) {
            salienceById.put(node.id(), node.salience());
        }
        Map<String, Double> chunkScore = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : reachedNodes.entrySet()) {
            String nodeId = entry.getKey();
            double nodeWeight = entry.getValue() * (0.5D + salienceById.getOrDefault(nodeId, 0.0D));
            jdbcTemplate.query("select chunk_id, weight from app_kg_node_chunk where node_id = ?",
                    rs -> {
                        String chunkId = rs.getString("chunk_id");
                        double w = rs.getDouble("weight");
                        chunkScore.merge(chunkId, nodeWeight * w, Double::sum);
                    }, nodeId);
        }
        return chunkScore;
    }

    // ---------------------------------------------------------------------
    // Viewer queries
    // ---------------------------------------------------------------------

    public RagSubgraph fullGraph(String organizationId, String nodeType, int limit) {
        List<RagGraphNodeItem> nodes = jdbcTemplate.query("""
                select id, name, node_type, description, salience, mention_count
                from app_kg_node where organization_id = ?
                """ + (nodeType == null || nodeType.isBlank() ? "" : " and node_type = ?") + """
                order by salience desc, mention_count desc
                """ + limitClause(limit),
                (rs, n) -> new RagGraphNodeItem(rs.getString("id"), rs.getString("name"),
                        rs.getString("node_type"), rs.getString("description"),
                        rs.getDouble("salience"), rs.getInt("mention_count")),
                nodeType == null || nodeType.isBlank()
                        ? new Object[]{organizationId} : new Object[]{organizationId, nodeType});
        Set<String> nodeIds = nodes.stream().map(RagGraphNodeItem::id).collect(java.util.stream.Collectors.toSet());
        List<RagGraphEdgeView> edges = jdbcTemplate.query("""
                select id, src_node_id, dst_node_id, relation, weight, evidence
                from app_kg_edge where organization_id = ?
                """, (rs, n) -> new RagGraphEdgeView(rs.getString("id"), rs.getString("src_node_id"),
                rs.getString("dst_node_id"), rs.getString("relation"), rs.getDouble("weight"),
                rs.getString("evidence")), organizationId)
                .stream().filter(e -> nodeIds.contains(e.source()) && nodeIds.contains(e.target())).toList();
        return new RagSubgraph(nodes, edges);
    }

    public RagSubgraph neighbors(String organizationId, String nodeName, int hops) {
        List<String> seeds = jdbcTemplate.query("""
                select id from app_kg_node where organization_id = ? and normalized_name = ?
                """, (rs, n) -> rs.getString("id"), organizationId, normalize(nodeName));
        if (seeds.isEmpty()) {
            return RagSubgraph.empty();
        }
        Map<String, Double> reached = bfsExpand(organizationId, seeds, Math.max(1, hops), 1.0D);
        Set<String> nodeIds = reached.keySet();
        List<RagGraphNodeItem> nodes = loadNodes(organizationId).stream()
                .filter(node -> nodeIds.contains(node.id()))
                .map(node -> new RagGraphNodeItem(node.id(), node.name(), node.nodeType(),
                        node.description(), node.salience(), node.mentionCount()))
                .toList();
        List<RagGraphEdgeView> edges = jdbcTemplate.query("""
                select id, src_node_id, dst_node_id, relation, weight, evidence
                from app_kg_edge where organization_id = ?
                """, (rs, n) -> new RagGraphEdgeView(rs.getString("id"), rs.getString("src_node_id"),
                rs.getString("dst_node_id"), rs.getString("relation"), rs.getDouble("weight"),
                rs.getString("evidence")), organizationId)
                .stream().filter(e -> nodeIds.contains(e.source()) && nodeIds.contains(e.target())).toList();
        return new RagSubgraph(nodes, edges);
    }

    public Map<String, Object> stats(String organizationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Integer nodeCount = jdbcTemplate.queryForObject(
                "select count(*) from app_kg_node where organization_id = ?", Integer.class, organizationId);
        Integer edgeCount = jdbcTemplate.queryForObject(
                "select count(*) from app_kg_edge where organization_id = ?", Integer.class, organizationId);
        Map<String, Integer> byType = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select node_type, count(*) c from app_kg_node where organization_id = ?
                group by node_type order by c desc
                """, rs -> { byType.put(rs.getString("node_type"), rs.getInt("c")); }, organizationId);
        result.put("nodeCount", nodeCount == null ? 0 : nodeCount);
        result.put("edgeCount", edgeCount == null ? 0 : edgeCount);
        result.put("nodesByType", byType);
        return result;
    }

    // ---------------------------------------------------------------------
    // Loading helpers
    // ---------------------------------------------------------------------

    private List<NodeRow> loadNodes(String organizationId) {
        return jdbcTemplate.query("""
                select id, name, normalized_name, node_type, description, salience, mention_count, embedding_json
                from app_kg_node where organization_id = ?
                """, (rs, n) -> new NodeRow(rs.getString("id"), rs.getString("name"),
                rs.getString("normalized_name"), rs.getString("node_type"), rs.getString("description"),
                rs.getDouble("salience"), rs.getInt("mention_count"),
                embeddingService.fromJson(rs.getString("embedding_json"))), organizationId);
    }

    private Map<String, List<Adj>> loadAdjacency(String organizationId) {
        Map<String, List<Adj>> adjacency = new HashMap<>();
        jdbcTemplate.query("select src_node_id, dst_node_id, weight from app_kg_edge where organization_id = ?",
                rs -> {
                    String src = rs.getString("src_node_id");
                    String dst = rs.getString("dst_node_id");
                    double weight = rs.getDouble("weight");
                    adjacency.computeIfAbsent(src, k -> new ArrayList<>()).add(new Adj(dst, weight));
                    adjacency.computeIfAbsent(dst, k -> new ArrayList<>()).add(new Adj(src, weight));
                }, organizationId);
        return adjacency;
    }

    private String limitClause(int limit) {
        return " limit " + Math.max(1, Math.min(2000, limit));
    }

    static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private String extractionSystemPrompt() {
        // Compact output (no description/evidence) keeps generation short, which matters a lot
        // on rate-limited endpoints. The graph still gets typed nodes + typed weighted edges.
        return """
                你是麒麟操作系统安全运维知识图谱抽取器。从文本抽取实体与关系，只输出紧凑 JSON，不要任何描述或原文引用。
                节点类型(type)限定：CONCEPT, COMPONENT, SERVICE, COMMAND, TOOL, FILE_PATH, CONFIG,
                RISK_LEVEL, POLICY, PROCEDURE, THREAT, ACCOUNT。
                关系类型(relation)限定：PART_OF, DEPENDS_ON, CONFIGURED_BY, MANAGED_BY, REQUIRES_APPROVAL,
                MITIGATES, DETECTS, AFFECTS, PROTECTS, RELATED_TO。
                只抽取确有依据的实体与关系，不编造，实体名用规范术语，每段最多 8 个实体、8 条关系。
                输出严格为：{"entities":[{"name":"","type":""}],"relations":[{"source":"","relation":"","target":""}]}
                """;
    }

    private record ChunkRef(String id, String content) {
    }

    private record NodeRow(String id, String name, String normalizedName, String nodeType,
                           String description, double salience, int mentionCount, List<Double> embedding) {
    }

    private record Adj(String target, double weight) {
    }

    private record ScoredNode(String id, double score) {
    }
}

package com.superskidder.kylinops.backend.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * RAG hybrid-retrieval benchmark: ingests the official Kylin corpus (real bge-m3 + Qwen graph
 * extraction + Elasticsearch), then evaluates a channel ablation (vector / ES / graph and their
 * fusions) over a labelled query set. Writes results JSON + a markdown report.
 *
 * Opt-in: run with RAG_BENCH=1, the rag-local.yml stack up, and .secrets/models.env present:
 *   RAG_BENCH=1 mvn -o test -Dtest=RagHybridBenchmarkIT
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RAG_BENCH", matches = "1")
class RagHybridBenchmarkIT {

    private static final String ORG = "org-alpha";

    // Persist to a file DB so the (slow) one-time graph build survives restarts and is resumable.
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./target/rag-bench-db;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false;AUTO_SERVER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RagService ragService;
    @Autowired
    private ObjectMapper objectMapper;

    private record AblationConfig(String name, boolean v, boolean k, boolean g) {
    }

    private static final List<AblationConfig> ABLATIONS = List.of(
            new AblationConfig("vector", true, false, false),
            new AblationConfig("keyword(ES)", false, true, false),
            new AblationConfig("graph", false, false, true),
            new AblationConfig("vector+keyword", true, true, false),
            new AblationConfig("vector+keyword+graph", true, true, true)
    );

    @Test
    void runBenchmark() throws IOException {
        Map<String, String> env = RagBenchSupport.loadModelsEnv();
        RagBenchSupport.seedModelConfig(jdbcTemplate, env, "kylinops-rag-bench");

        JsonNode spec = objectMapper.readTree(Files.readString(RagBenchSupport.resolve("bench/rag/queries.json")));

        // ---- Ingest corpus (chunks + bge-m3 embeddings + Elasticsearch + LLM graph) ----
        Map<String, String> docKeyToDocId = new LinkedHashMap<>();
        long ingestStart = System.currentTimeMillis();
        JsonNode docs = spec.get("docs");
        int n = 0;
        for (var it = docs.fieldNames(); it.hasNext(); ) {
            String docKey = it.next();
            JsonNode d = docs.get(docKey);
            n++;
            String title = d.get("title").asText();
            List<String> existing = jdbcTemplate.query("""
                    select d.id from app_rag_document d
                    where d.organization_id = ? and d.title = ?
                      and exists (select 1 from app_rag_chunk c where c.document_id = d.id)
                    """, (rs, i) -> rs.getString("id"), ORG, title);
            if (!existing.isEmpty()) {
                String existId = existing.get(0);
                Integer edges = jdbcTemplate.queryForObject(
                        "select count(*) from app_kg_edge where organization_id = ? and source_document_id = ?",
                        Integer.class, ORG, existId);
                if (edges != null && edges > 0) {
                    docKeyToDocId.put(docKey, existId);
                    System.out.printf("[BENCH] skip %d/%d %s (built, %d edges)%n", n, docs.size(), docKey, edges);
                    continue;
                }
                // Chunks present but no graph (killed mid-build): delete and re-ingest cleanly.
                ragService.deleteDocument(ORG, existId);
                System.out.printf("[BENCH] re-ingest %d/%d %s (partial graph)%n", n, docs.size(), docKey);
            }
            Path file = RagBenchSupport.corpusFile(d.get("file").asText());
            String content = RagBenchSupport.stripFrontMatter(Files.readString(file));
            long t0 = System.currentTimeMillis();
            RagDocumentItem doc = ragService.createDocument(ORG,
                    new RagDocumentCreateRequest(title, "MANUAL", content));
            docKeyToDocId.put(docKey, doc.id());
            System.out.printf("[BENCH] ingested %d/%d %s (%.1fs)%n", n, docs.size(), docKey,
                    (System.currentTimeMillis() - t0) / 1000.0);
        }
        System.out.printf("[BENCH] ingest done in %.1fs%n", (System.currentTimeMillis() - ingestStart) / 1000.0);

        Map<String, Object> graphStats = ragService.graphStats(ORG);
        int chunkTotal = jdbcTemplate.queryForObject(
                "select count(*) from app_rag_chunk where organization_id = ?", Integer.class, ORG);

        // ---- Load chunks (id, doc, content) for gold computation ----
        List<ChunkRow> chunkRows = jdbcTemplate.query(
                "select id, document_id, content from app_rag_chunk where organization_id = ?",
                (rs, i) -> new ChunkRow(rs.getString("id"), rs.getString("document_id"), rs.getString("content")), ORG);

        // ---- Build gold per positive query ----
        List<QueryCase> cases = new ArrayList<>();
        int negatives = 0;
        for (JsonNode q : spec.get("queries")) {
            Set<String> goldDocIds = new HashSet<>();
            for (JsonNode dk : q.get("goldDocs")) {
                String id = docKeyToDocId.get(dk.asText());
                if (id != null) {
                    goldDocIds.add(id);
                }
            }
            List<String> keywords = new ArrayList<>();
            for (JsonNode kw : q.get("goldKeywords")) {
                keywords.add(kw.asText().toLowerCase(Locale.ROOT));
            }
            Set<String> gold = new HashSet<>();
            if (!goldDocIds.isEmpty()) {
                for (ChunkRow c : chunkRows) {
                    if (goldDocIds.contains(c.documentId())) {
                        String lower = c.content().toLowerCase(Locale.ROOT);
                        if (keywords.stream().anyMatch(lower::contains)) {
                            gold.add(c.id());
                        }
                    }
                }
            }
            boolean negative = q.get("goldDocs").isEmpty();
            if (negative) {
                negatives++;
            }
            cases.add(new QueryCase(q.get("id").asText(), q.get("bucket").asText(),
                    q.get("q").asText(), gold, negative));
        }

        long emptyGold = cases.stream().filter(c -> !c.negative() && c.gold().isEmpty()).count();
        System.out.printf("[BENCH] chunks=%d graph=%s positives=%d negatives=%d emptyGold=%d%n",
                chunkTotal, graphStats, cases.size() - negatives, negatives, emptyGold);

        // ---- Run ablations ----
        Map<String, Agg> results = new LinkedHashMap<>();
        for (AblationConfig ab : ABLATIONS) {
            Agg agg = new Agg();
            for (QueryCase c : cases) {
                if (c.negative() || c.gold().isEmpty()) {
                    continue;
                }
                RagService.RetrievalConfig cfg = new RagService.RetrievalConfig(
                        ab.v(), ab.k(), ab.g(), 1.0, 1.0, 0.8, 60, 50, 10);
                long t0 = System.nanoTime();
                RagSearchContext ctx = ragService.retrieveContext(ORG, c.question(), cfg);
                double ms = (System.nanoTime() - t0) / 1_000_000.0;
                List<String> ranked = ctx.chunks().stream().map(RagChunkItem::id).toList();
                agg.add(ranked, c.gold(), ms);
            }
            results.put(ab.name(), agg);
            System.out.printf("[BENCH] %-22s R@5=%.3f nDCG@10=%.3f MRR@10=%.3f MAP@10=%.3f P95ms=%.0f%n",
                    ab.name(), agg.mean(agg.recall5), agg.mean(agg.ndcg10), agg.mean(agg.mrr10),
                    agg.mean(agg.map10), agg.p95Latency());
        }

        writeReport(results, cases, negatives, emptyGold, chunkTotal, graphStats, env);
    }

    private void writeReport(Map<String, Agg> results, List<QueryCase> cases, int negatives,
                             long emptyGold, int chunkTotal, Map<String, Object> graphStats,
                             Map<String, String> env) throws IOException {
        int positives = cases.size() - negatives - (int) emptyGold;
        StringBuilder md = new StringBuilder();
        md.append("# RAG 三路混合检索 Benchmark 报告\n\n");
        md.append("> 自动生成（`RagHybridBenchmarkIT`），数据真实，未人工修改。\n\n");
        md.append("## 实验设置\n\n");
        md.append("- Embedding：`").append(env.getOrDefault("RAG_EMBEDDING_MODEL", "BAAI/bge-m3"))
                .append("`（").append(env.getOrDefault("RAG_EMBEDDING_DIM", "1024")).append(" 维，真实语义向量）\n");
        md.append("- 图谱抽取 / LLM：`").append(env.getOrDefault("LLM_CHAT_MODEL", "Qwen/Qwen2.5-72B-Instruct")).append("`\n");
        md.append("- 关键词通道：Elasticsearch 8.15 BM25（chunk 级，CJK 分词）\n");
        md.append("- 图通道：k-hop(2) 邻域 BFS（衰减 0.5）+ node→chunk 证据回链\n");
        md.append("- 融合：RRF（k=60，权重 V/K/G = 1.0/1.0/0.8）\n");
        md.append("- 语料：官方麒麟文档；chunk 总数 **").append(chunkTotal).append("**；知识图谱 ")
                .append(graphStats).append("\n");
        md.append("- 标注：章节溯源弱标注（gold = 命中文档内含概念关键词的 chunk）。有效正样本 **")
                .append(positives).append("**，负样本 ").append(negatives).append("。\n\n");
        md.append("## 检索指标（按通道消融，均值）\n\n");
        md.append("| 配置 | Recall@5 | Recall@10 | P@5 | Hit@5 | MRR@10 | nDCG@10 | MAP@10 | 延迟P95(ms) |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (var e : results.entrySet()) {
            Agg a = e.getValue();
            md.append("| ").append(e.getKey())
                    .append(" | ").append(fmt(a.mean(a.recall5)))
                    .append(" | ").append(fmt(a.mean(a.recall10)))
                    .append(" | ").append(fmt(a.mean(a.p5)))
                    .append(" | ").append(fmt(a.mean(a.hit5)))
                    .append(" | ").append(fmt(a.mean(a.mrr10)))
                    .append(" | ").append(fmt(a.mean(a.ndcg10)))
                    .append(" | ").append(fmt(a.mean(a.map10)))
                    .append(" | ").append(String.format(Locale.ROOT, "%.0f", a.p95Latency()))
                    .append(" |\n");
        }
        md.append("\n说明：标注对图通道保守（图召回但不含字面关键词的 chunk 不计 gold），故图通道增益为下界。\n");

        Path reportDir = RagBenchSupport.resolve("bench/rag").resolve("results");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("results.json"), toJson(results));
        Path doc = RagBenchSupport.resolve("docs/05-评估方案").resolve("RAG-Benchmark报告.md");
        Files.writeString(doc, md.toString());
        System.out.println("[BENCH] report written: " + doc.toAbsolutePath());
    }

    private String toJson(Map<String, Agg> results) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (var e : results.entrySet()) {
            Agg a = e.getValue();
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("recall@5", a.mean(a.recall5));
            m.put("recall@10", a.mean(a.recall10));
            m.put("precision@5", a.mean(a.p5));
            m.put("hit@5", a.mean(a.hit5));
            m.put("mrr@10", a.mean(a.mrr10));
            m.put("ndcg@10", a.mean(a.ndcg10));
            m.put("map@10", a.mean(a.map10));
            m.put("latencyP95ms", a.p95Latency());
            out.put(e.getKey(), m);
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private record ChunkRow(String id, String documentId, String content) {
    }

    private record QueryCase(String id, String bucket, String question, Set<String> gold, boolean negative) {
    }

    private static final class Agg {
        final List<Double> recall5 = new ArrayList<>();
        final List<Double> recall10 = new ArrayList<>();
        final List<Double> p5 = new ArrayList<>();
        final List<Double> hit5 = new ArrayList<>();
        final List<Double> mrr10 = new ArrayList<>();
        final List<Double> ndcg10 = new ArrayList<>();
        final List<Double> map10 = new ArrayList<>();
        final List<Double> latency = new ArrayList<>();

        void add(List<String> ranked, Set<String> gold, double ms) {
            recall5.add(RagMetrics.recallAtK(ranked, gold, 5));
            recall10.add(RagMetrics.recallAtK(ranked, gold, 10));
            p5.add(RagMetrics.precisionAtK(ranked, gold, 5));
            hit5.add(RagMetrics.hitAtK(ranked, gold, 5));
            mrr10.add(RagMetrics.reciprocalRank(ranked, gold, 10));
            ndcg10.add(RagMetrics.ndcgAtK(ranked, gold, 10));
            map10.add(RagMetrics.averagePrecision(ranked, gold, 10));
            latency.add(ms);
        }

        double mean(List<Double> xs) {
            return xs.isEmpty() ? 0.0 : xs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        double p95Latency() {
            if (latency.isEmpty()) {
                return 0.0;
            }
            List<Double> sorted = latency.stream().sorted().toList();
            int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }
    }
}

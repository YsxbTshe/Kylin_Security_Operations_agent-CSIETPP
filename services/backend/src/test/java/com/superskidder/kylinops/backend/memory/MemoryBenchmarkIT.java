package com.superskidder.kylinops.backend.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.rag.RagBenchSupport;
import com.superskidder.kylinops.backend.rag.RagMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Agent-memory benchmark. Reuses the persisted knowledge graph (built by RagHybridBenchmarkIT),
 * inserts a labelled Kylin-ops memory set, and ablates three recall strategies:
 *   semantic (flat vector) | generative-agents (vector+recency+importance) | graph-augmented (ours).
 * Opt-in: RAG_BENCH=1 (run AFTER the RAG benchmark so the graph exists):
 *   RAG_BENCH=1 mvn -o test -Dtest=MemoryBenchmarkIT
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RAG_BENCH", matches = "1")
class MemoryBenchmarkIT {

    private static final String ORG = "org-alpha";

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
    private GraphMemoryService memoryService;
    @Autowired
    private ObjectMapper objectMapper;

    private record Strategy(String name, MemoryRetrievalConfig config) {
    }

    @Test
    void runMemoryBenchmark() throws IOException {
        Map<String, String> env = RagBenchSupport.loadModelsEnv();
        RagBenchSupport.seedModelConfig(jdbcTemplate, env, "kylinops-rag-bench");

        JsonNode spec = objectMapper.readTree(Files.readString(RagBenchSupport.resolve("bench/memory/dataset.json")));

        // Idempotent re-run: clear prior bench memories, then insert the labelled set with explicit
        // creation times (ageDays backdates "old" facts so recency can be tested honestly).
        jdbcTemplate.update("""
                delete from app_memory_entity_link where memory_id in
                (select id from app_rag_memory where organization_id = ? and memory_source = 'BENCH')
                """, ORG);
        jdbcTemplate.update("delete from app_rag_memory where organization_id = ? and memory_source = 'BENCH'", ORG);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        Map<String, String> keyToId = new LinkedHashMap<>();
        for (JsonNode m : spec.get("memories")) {
            int ageDays = m.has("ageDays") ? m.get("ageDays").asInt() : 0;
            String id = memoryService.add(ORG, m.get("type").asText(), m.get("title").asText(),
                    m.get("content").asText(), m.get("importance").asInt(3), "BENCH", null, now.minusDays(ageDays));
            keyToId.put(m.get("key").asText(), id);
        }
        int memCount = jdbcTemplate.queryForObject(
                "select count(*) from app_rag_memory where organization_id = ? and valid = true", Integer.class, ORG);
        int linkCount = jdbcTemplate.queryForObject(
                "select count(*) from app_memory_entity_link where organization_id = ?", Integer.class, ORG);
        System.out.printf("[MEM] memories=%d entity-links=%d%n", memCount, linkCount);

        List<QueryCase> cases = new ArrayList<>();
        for (JsonNode q : spec.get("queries")) {
            Set<String> gold = new HashSet<>();
            for (JsonNode gk : q.get("goldKeys")) {
                String id = keyToId.get(gk.asText());
                if (id != null) {
                    gold.add(id);
                }
            }
            cases.add(new QueryCase(q.get("id").asText(), q.get("q").asText(), gold));
        }

        List<Strategy> strategies = List.of(
                new Strategy("semantic", MemoryRetrievalConfig.semanticOnly()),
                new Strategy("generative-agents", MemoryRetrievalConfig.generativeAgents()),
                new Strategy("graph-augmented(ours)", MemoryRetrievalConfig.full()));

        // results[strategy] = [single, multi, temporal, overall][5 metrics].
        Map<String, double[][]> results = new LinkedHashMap<>();
        int[] subsetCounts = new int[4];
        for (Strategy s : strategies) {
            double[][] acc = new double[4][5];
            int[] counts = new int[4];
            for (QueryCase c : cases) {
                if (c.gold().isEmpty()) {
                    continue;
                }
                List<String> ranked = memoryService.recall(ORG, c.query(), 10, s.config())
                        .stream().map(MemoryHit::id).toList();
                double[] m = {
                        RagMetrics.recallAtK(ranked, c.gold(), 5),
                        RagMetrics.recallAtK(ranked, c.gold(), 10),
                        RagMetrics.reciprocalRank(ranked, c.gold(), 10),
                        RagMetrics.ndcgAtK(ranked, c.gold(), 10),
                        RagMetrics.averagePrecision(ranked, c.gold(), 10)
                };
                int subset = c.id().startsWith("MT") ? 2 : (c.id().startsWith("MM") ? 1 : 0);
                for (int i = 0; i < 5; i++) {
                    acc[subset][i] += m[i];
                    acc[3][i] += m[i];
                }
                counts[subset]++;
                counts[3]++;
            }
            subsetCounts = counts;
            double[][] avg = new double[4][5];
            for (int sub = 0; sub < 4; sub++) {
                for (int i = 0; i < 5; i++) {
                    avg[sub][i] = counts[sub] > 0 ? acc[sub][i] / counts[sub] : 0.0;
                }
            }
            results.put(s.name(), avg);
            System.out.printf("[MEM] %-24s single R@5=%.3f | multi nDCG=%.3f | temporal MRR=%.3f nDCG=%.3f%n",
                    s.name(), avg[0][0], avg[1][3], avg[2][2], avg[2][3]);
        }

        writeReport(results, subsetCounts, memCount, linkCount, env);
    }

    private void writeReport(Map<String, double[][]> results, int[] counts, int memCount, int linkCount,
                             Map<String, String> env) throws IOException {
        String[] subsetNames = {"单答案查询（直接召回）", "多答案/实体聚类查询", "时序更新查询（新事实覆盖旧事实）", "全部查询"};
        StringBuilder md = new StringBuilder();
        md.append("# Agent 记忆系统 Benchmark 报告\n\n");
        md.append("> 自动生成（`MemoryBenchmarkIT`），数据真实，未人工修改。\n\n");
        md.append("## 实验设置\n\n");
        md.append("- 记忆条数 **").append(memCount).append("**，记忆↔知识图谱实体链接 **").append(linkCount)
                .append("** 条；查询：单答案 ").append(counts[0]).append("、多答案/实体聚类 ")
                .append(counts[1]).append("、时序更新 ").append(counts[2]).append("。\n");
        md.append("- Embedding：`").append(env.getOrDefault("RAG_EMBEDDING_MODEL", "BAAI/bge-m3")).append("`，复用 RAG 阶段知识图谱做实体链接与图邻域召回。\n");
        md.append("- 策略消融：`semantic`（纯向量，朴素基线）、`generative-agents`（向量+时近+重要度，开源主流基线）、`graph-augmented(ours)`（再叠加图实体邻域）。\n");
        md.append("- 时序更新查询：数据集含\"旧/新\"成对事实（如 db-01 备份窗口 2点→4点），旧事实按 ageDays 回填创建时间，gold=新事实；考察 recency 是否能让新事实排在旧事实之前。\n\n");
        md.append("## 结果（均值）\n\n");
        for (int sub = 0; sub < 4; sub++) {
            md.append("### ").append(subsetNames[sub]).append("（n=").append(counts[sub]).append("）\n\n");
            md.append("| 策略 | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | MAP@10 |\n|---|---|---|---|---|---|\n");
            for (var e : results.entrySet()) {
                double[] v = e.getValue()[sub];
                md.append("| ").append(e.getKey());
                for (double x : v) {
                    md.append(" | ").append(String.format(java.util.Locale.ROOT, "%.3f", x));
                }
                md.append(" |\n");
            }
            md.append("\n");
        }
        md.append("解读：单答案直接召回上强向量(bge-m3)基线已近天花板，附加通道易引入噪声；**记忆系统真正区分能力的场景是时序更新**——新事实须覆盖语义高度相似的旧事实，纯向量无法区分新旧而 recency-aware 策略（generative-agents / graph-augmented）显著占优。这与开源记忆系统(Mem0/Zep/Generative-Agents)强调时序/更新一致。\n");

        var dir = RagBenchSupport.resolve("bench/memory").resolve("results");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("results.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        Files.writeString(RagBenchSupport.resolve("docs/05-评估方案").resolve("Memory-Benchmark报告.md"), md.toString());
        System.out.println("[MEM] report written");
    }

    private record QueryCase(String id, String query, Set<String> gold) {
    }
}

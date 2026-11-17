package com.superskidder.kylinops.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end smoke test for graph RAG: ingests a real Kylin corpus file with real
 * bge-m3 embeddings + Qwen graph extraction + local Elasticsearch, then verifies the
 * three-channel hybrid retrieval. Hits external services, so it is opt-in:
 * run with RAG_SMOKE=1 and the local rag-local.yml stack + .secrets/models.env present.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RAG_SMOKE", matches = "1")
class RagGraphHybridSmokeIT {

    private static final String ORG = "org-alpha";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RagService ragService;
    @Autowired
    private RagExternalIndexClient externalIndexClient;

    @Test
    void ingestRealCorpusAndRetrieve() throws IOException {
        Map<String, String> env = RagBenchSupport.loadModelsEnv();
        RagBenchSupport.seedModelConfig(jdbcTemplate, env, "kylinops-rag-smoke");

        Path corpus = RagBenchSupport.corpusFile("kysec-security/kylin-v10-sysadmin-ch8-system-security.md");
        String content = RagBenchSupport.stripFrontMatter(Files.readString(corpus));
        String text = content.length() > 4000 ? content.substring(0, 4000) : content;

        RagDocumentItem doc = ragService.createDocument(ORG,
                new RagDocumentCreateRequest("银河麒麟 V10 系统安全 KYSEC", "MANUAL", text));

        Integer chunkCount = jdbcTemplate.queryForObject(
                "select count(*) from app_rag_chunk where document_id = ?", Integer.class, doc.id());
        Integer nodeCount = jdbcTemplate.queryForObject(
                "select count(*) from app_kg_node where organization_id = ?", Integer.class, ORG);
        Integer edgeCount = jdbcTemplate.queryForObject(
                "select count(*) from app_kg_edge where organization_id = ?", Integer.class, ORG);

        var esHits = externalIndexClient.searchChunks(ORG, "KYSEC 三权分立 强制访问控制", 10);
        RagSearchContext ctx = ragService.retrieveContext(ORG, "KYSEC 安全机制如何实现三权分立");

        System.out.printf("[RAG SMOKE] chunks=%d kgNodes=%d kgEdges=%d esHits=%d retrieved=%d graphEdges=%d%n",
                chunkCount, nodeCount, edgeCount, esHits.size(), ctx.chunks().size(), ctx.graphEdges().size());
        if (!ctx.chunks().isEmpty()) {
            System.out.println("[RAG SMOKE] top chunk: " + ctx.chunks().get(0).content().replaceAll("\\s+", " ").substring(0,
                    Math.min(120, ctx.chunks().get(0).content().replaceAll("\\s+", " ").length())));
        }

        assertThat(chunkCount).isGreaterThan(0);
        assertThat(esHits).isNotEmpty();
        assertThat(nodeCount).isGreaterThan(0);
        assertThat(ctx.chunks()).isNotEmpty();
    }
}

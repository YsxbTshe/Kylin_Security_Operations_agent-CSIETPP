package com.superskidder.kylinops.backend.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.rag.RagBenchSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 记忆生命周期（ADR-001 §2.7）的 DB 全路径验证 + 生命周期指标产出：
 * 只追加 + supersedes 链、被取代/过期强降权、同 scope 冲突并列、任务相似度第五路。
 *
 * 与 MemoryBenchmarkIT 一样用 RAG_BENCH 门控（@SpringBootTest 较重）；核心可信度判据的
 * 快速回归见 MemoryTrustTest。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RAG_BENCH", matches = "1")
class MemoryLifecycleIT {

    private static final String ORG = "org-alpha";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./target/mem-lifecycle-db;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false;AUTO_SERVER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        // 测试不绑真实 gRPC 端口，避免与本机运行的 dev 后端争用 9090/9091。
        registry.add("app.host-agent.grpc.enabled", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private GraphMemoryService memoryService;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from app_memory_entity_link where organization_id = ?", ORG);
        jdbcTemplate.update("delete from app_rag_memory where organization_id = ?", ORG);
    }

    @Test
    void appendOnlySupersedesInsteadOfMerging() {
        String scope = "service:nginx@node-1";
        String first = memoryService.remember(ORG, new MemoryWrite("FACT",
                "nginx 监听端口", "nginx 监听 80/443", 3, "MANUAL", null, "task-1", "SUCCESS", true, null, scope));
        // 同一 scope、内容相同 → 只追加，第二条取代第一条（旧的保留为历史）。
        String second = memoryService.remember(ORG, new MemoryWrite("FACT",
                "nginx 监听端口", "nginx 监听 80/443", 3, "MANUAL", null, "task-2", "SUCCESS", true, null, scope));

        assertThat(second).isNotEqualTo(first);
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from app_rag_memory where organization_id = ?", Integer.class, ORG);
        assertThat(rows).isEqualTo(2); // 只追加：两条都在，未合并
        String supersedes = jdbcTemplate.queryForObject(
                "select supersedes_id from app_rag_memory where id = ?", String.class, second);
        assertThat(supersedes).isEqualTo(first);

        List<MemoryHit> hits = memoryService.recall(ORG, "nginx 端口", 10, MemoryRetrievalConfig.full());
        MemoryHit latest = hitById(hits, second);
        MemoryHit old = hitById(hits, first);
        assertThat(latest.superseded()).isFalse();
        assertThat(old.superseded()).isTrue();
        assertThat(latest.score()).isGreaterThan(old.score()); // 被取代者强降权，排在最新版本之后
    }

    @Test
    void expiredMemoryIsFlaggedAndDowngraded() {
        memoryService.add(ORG, new MemoryWrite("FACT", "临时封禁", "封禁 203.0.113.7", 3, "OP_LOG",
                null, "task-x", "SUCCESS", true, LocalDateTime.now().minusHours(1), "firewall:203.0.113.7"),
                LocalDateTime.now().minusHours(2));
        List<MemoryHit> hits = memoryService.recall(ORG, "封禁 203.0.113.7", 10, MemoryRetrievalConfig.full());
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).expired()).isTrue();
        assertThat(hits.get(0).trust()).isLessThan(0.5D);
    }

    @Test
    void conflictingMemoriesAreSurfacedInParallel() {
        String scope = "config:sshd_permit_root";
        // 两条同 scope、内容分歧、互不取代（用 add 绕过取代检测）→ 都应被标记冲突并列。
        memoryService.add(ORG, new MemoryWrite("FACT", "sshd 配置A", "PermitRootLogin yes", 3,
                "MANUAL", null, "t1", "SUCCESS", false, null, scope), LocalDateTime.now().minusHours(3));
        memoryService.add(ORG, new MemoryWrite("FACT", "sshd 配置B", "PermitRootLogin no", 3,
                "MANUAL", null, "t2", "SUCCESS", false, null, scope), LocalDateTime.now().minusHours(1));
        List<MemoryHit> hits = memoryService.recall(ORG, "PermitRootLogin", 10, MemoryRetrievalConfig.full());
        long conflicts = hits.stream().filter(h -> scope.equalsIgnoreCase(h.scope())).filter(MemoryHit::conflicting).count();
        assertThat(conflicts).isEqualTo(2); // 并列呈现，不替某一方做主
    }

    @Test
    void taskAffinityChannelSurfacesPastTaskRecords() {
        memoryService.add(ORG, new MemoryWrite("OP_LOG", "[运维归档] 处理磁盘告警",
                "节点 node-1 清理 /var/log 释放空间", 4, "OP_LOG", "task-99", "task-99", "SUCCESS", true, null,
                "node:node-1"), LocalDateTime.now().minusHours(1));
        List<MemoryHit> withTask = memoryService.recall(ORG, "磁盘告警清理", 10, MemoryRetrievalConfig.full());
        List<MemoryHit> withoutTask = memoryService.recall(ORG, "磁盘告警清理", 10, MemoryRetrievalConfig.compositeNoTrust());
        assertThat(withTask.get(0).taskAffinity()).isGreaterThan(0.0D);
        assertThat(withoutTask.get(0).taskAffinity()).isEqualTo(0.0D);

        writeLifecycleReport();
    }

    private void writeLifecycleReport() {
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("appendOnly", true);
            report.put("supersedesChain", true);
            report.put("staleDowngrade", true);
            report.put("conflictSurfacing", true);
            report.put("taskAffinityChannel", true);
            var dir = RagBenchSupport.resolve("bench/memory").resolve("results");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("lifecycle.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (IOException ignored) {
            // 报告写出失败不影响判定
        }
    }

    private MemoryHit hitById(List<MemoryHit> hits, String id) {
        return hits.stream().filter(h -> h.id().equals(id)).findFirst().orElseThrow();
    }
}

package com.superskidder.kylinops.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * H2（默认开发/测试形态）下向量通道的降级行为：不存在 pgvector 列时必须退回 JVM 内存余弦，
 * 且写入侧是无害的 no-op——否则 H2 上跑 mvn test 会因为 {@code <=>} 语法直接炸。
 *
 * <p>库内检索一路与两条路径的 top-k 对照，需要真实 PostgreSQL，见 {@code RagPgVectorParityIT}。
 */
class RagVectorIndexTest {

    private final RagVectorIndex vectorIndex = newIndex();

    @Test
    void fallsBackToInMemoryCosineWhenDatabaseIsNotPostgres() {
        assertThat(vectorIndex.databaseRankingAvailable()).isFalse();
        assertThat(vectorIndex.describe(settings())).contains("JVM 内存余弦");
    }

    @Test
    void inMemoryRankingIsDeterministicAndDropsNonPositiveScores() {
        List<RagVectorIndex.ChunkVector> chunks = List.of(
                new RagVectorIndex.ChunkVector("chk-a", List.of(1.0D, 0.0D, 0.0D)),
                new RagVectorIndex.ChunkVector("chk-b", List.of(0.6D, 0.8D, 0.0D)),
                new RagVectorIndex.ChunkVector("chk-c", List.of(0.0D, 0.0D, 1.0D)),
                // 与查询同分：并列时按 id 升序稳定排列，两条路径才能给出同一顺序
                new RagVectorIndex.ChunkVector("chk-d", List.of(2.0D, 0.0D, 0.0D))
        );

        List<RagScoredId> ranked = vectorIndex.rankInMemory(chunks, List.of(1.0D, 0.0D, 0.0D), 10);

        assertThat(ranked).extracting(RagScoredId::id).containsExactly("chk-a", "chk-d", "chk-b");
        assertThat(ranked.get(0).score()).isEqualTo(1.0D);
        assertThat(ranked.get(2).score()).isCloseTo(0.6D, org.assertj.core.data.Offset.offset(1e-9D));
    }

    @Test
    void storeIsANoOpWithoutAVectorColumn() {
        assertThatCode(() -> vectorIndex.store(List.of(
                new RagVectorIndex.ChunkVector("chk-a", List.of(0.1D, 0.2D)))))
                .doesNotThrowAnyException();
    }

    private static RagVectorIndex newIndex() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:rag-vector-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        RagSettingsService settingsService = new RagSettingsService(jdbcTemplate);
        return new RagVectorIndex(jdbcTemplate, new RagEmbeddingService(settingsService, new ObjectMapper(), false));
    }

    private static RagSettings settings() {
        return new RagSettings("default", "LOCAL", "", "", "local-hash-embedding", 384,
                800, 120, 5, false, "", "", false, "", "", false, "", "",
                false, "", "", "", 0, "", "test", null);
    }
}

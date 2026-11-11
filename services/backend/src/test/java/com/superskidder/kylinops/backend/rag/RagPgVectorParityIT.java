package com.superskidder.kylinops.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * pgvector 库内检索 与 JVM 内存余弦 的**结果对照**。
 *
 * <p>向量通道有两条实现（PostgreSQL 走 {@code <=>} + HNSW，H2 走内存逐条余弦），
 * 一旦两边打分口径漂移，就会出现"同一个问题换个库答案不一样"却没人发现。本测试把
 * 同一批向量、同一个查询喂给两条路径，要求 **top-k 的 id 序列完全一致、逐条分数差 < 1e-6**。
 *
 * <p>需要真实的 PostgreSQL + pgvector（{@code application-postgres-it.yml} 从
 * {@code POSTGRES_IT_JDBC_URL} 取连接串），故按该环境变量门控，未设置时整类跳过：
 * <pre>
 * docker-compose -f deploy/docker/rag-local.yml up -d pgvector
 * POSTGRES_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:5433/kylinops \
 *   mvn -B -Dtest=RagPgVectorParityIT -DfailIfNoTests=false test
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("postgres-it")
@EnabledIfEnvironmentVariable(named = "POSTGRES_IT_JDBC_URL", matches = ".+")
class RagPgVectorParityIT {

    private static final String ORG = "org-alpha";

    /** 语料刻意各写各的主题，让 top-k 之间分数拉开，避免并列名次让"顺序一致"变成运气问题。 */
    private static final List<String[]> CORPUS = List.of(
            new String[]{"KYSEC 强制访问控制", "麒麟 KYSEC 提供强制访问控制与三权分立，setstatus 可切换 exec 控制模式。"},
            new String[]{"防火墙端口放行", "firewalld 通过 firewall-cmd --add-port 放行端口，reload 后生效，zone 决定作用范围。"},
            new String[]{"磁盘容量巡检", "df -h 查看文件系统使用率，inode 耗尽时 df -i 才能看出问题，日志目录最常打满。"},
            new String[]{"systemd 服务状态", "systemctl status 查看服务运行状态，journalctl -u 查看单元日志与失败原因。"},
            new String[]{"用户与权限审计", "lastlog 与 auditd 记录登录与提权行为，sudoers 变更必须留审计轨迹。"},
            new String[]{"内核参数调优", "sysctl -w 临时生效，写入 sysctl.conf 后 sysctl -p 持久化，涉及网络与内存水位。"}
    );

    private static final List<String> QUERIES = List.of(
            "KYSEC 强制访问控制怎么开启",
            "端口放行需要 reload 吗",
            "磁盘满了怎么排查",
            "服务起不来看哪里的日志"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RagService ragService;
    @Autowired
    private RagVectorIndex vectorIndex;
    @Autowired
    private RagEmbeddingService embeddingService;

    @Test
    void databaseAndInMemoryVectorChannelsAgreeOnTopK() {
        assertThat(vectorIndex.databaseRankingAvailable())
                .as("postgres-it profile 下必须走库内检索，否则这个对照测试等于什么都没测")
                .isTrue();

        List<String> createdDocumentIds = new ArrayList<>();
        try {
            for (String[] entry : CORPUS) {
                createdDocumentIds.add(ragService.createDocument(ORG,
                        new RagDocumentCreateRequest(entry[0], "MANUAL", entry[1])).id());
            }
            // 库里可能残留更早版本写入、embedding 列为空的分片；全量重建让两条路径看到同一批向量。
            ragService.rebuildAll(ORG);

            Integer missingVectors = jdbcTemplate.queryForObject(
                    "select count(*) from app_rag_chunk where organization_id = ? and embedding is null",
                    Integer.class, ORG);
            assertThat(missingVectors).as("写入侧必须把向量真的落进 pgvector 列").isZero();

            List<RagVectorIndex.ChunkVector> chunks = jdbcTemplate.query("""
                    select id, embedding_json from app_rag_chunk where organization_id = ?
                    """, (rs, rowNum) -> new RagVectorIndex.ChunkVector(
                    rs.getString("id"), embeddingService.fromJson(rs.getString("embedding_json"))), ORG);
            assertThat(chunks).hasSizeGreaterThanOrEqualTo(CORPUS.size());

            for (String question : QUERIES) {
                List<Double> queryEmbedding = embeddingService.embed(question);
                List<RagScoredId> fromDatabase = vectorIndex.rankInDatabase(ORG, queryEmbedding, 10);
                List<RagScoredId> fromMemory = vectorIndex.rankInMemory(chunks, queryEmbedding, 10);

                assertThat(fromDatabase).as("查询「%s」在 PG 上应有命中", question).isNotEmpty();
                assertThat(fromDatabase.stream().map(RagScoredId::id).toList())
                        .as("查询「%s」的 top-k 顺序：pgvector 与内存余弦必须一致", question)
                        .isEqualTo(fromMemory.stream().map(RagScoredId::id).toList());
                for (int index = 0; index < fromDatabase.size(); index++) {
                    assertThat(fromDatabase.get(index).score())
                            .as("查询「%s」第 %d 条的相似度", question, index + 1)
                            .isCloseTo(fromMemory.get(index).score(), org.assertj.core.data.Offset.offset(1e-6D));
                }
            }
        } finally {
            for (String documentId : createdDocumentIds) {
                jdbcTemplate.update("delete from app_rag_document where id = ?", documentId);
            }
        }
    }

    @Test
    void reportsDimensionMismatchInsteadOfSilentlyTruncating() {
        assertThat(vectorIndex.databaseRankingAvailable()).isTrue();

        // 旧实现会拿 min(len) 截断算余弦：换 embedding 模型后检索照常返回结果，
        // 但比较的是两个不相干语义空间的前 N 维。现在必须直接报错并给出重建指引。
        assertThatThrownBy(() -> vectorIndex.rankInDatabase(ORG, Collections.nCopies(8, 0.5D), 5))
                .hasMessageContaining("向量维度不匹配")
                .hasMessageContaining("vector(8)")
                .hasMessageContaining("/api/v1/rag/rebuild");
    }
}

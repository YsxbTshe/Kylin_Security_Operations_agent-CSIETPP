package com.superskidder.kylinops.backend.rag;

import com.superskidder.kylinops.backend.common.AppException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 向量通道的两条实现：PostgreSQL/pgvector 的库内检索，与 H2 上的 JVM 内存余弦降级。
 *
 * <p>两条路径的**打分口径必须一致**，否则"换个库 top-k 就变了"就没法解释：
 * 都用余弦相似度，SQL 侧取 {@code 1 - (embedding <=> query)}（{@code <=>} 是
 * pgvector 的余弦距离算子，索引为 {@code vector_cosine_ops}），JVM 侧用
 * {@link RagEmbeddingService#cosine}；都按相似度降序、同分按 id 升序稳定排列，
 * 都丢弃非正相似度。对照测试见 {@code RagPgVectorParityIT}。
 *
 * <p>维度不匹配（换 embedding 模型但没重建向量列）时**硬失败**而不是降级：
 * 旧实现在 JVM 里按 {@code min(len)} 截断算余弦，换模型后检索会静默地按前 384 维
 * 比较两个语义空间完全不同的向量——结果看着正常、其实是噪声。宁可报错并给出重建指引。
 */
@Component
public class RagVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(RagVectorIndex.class);

    /** {@code format_type} 对 pgvector 列返回形如 {@code vector(384)}。 */
    private static final Pattern VECTOR_TYPE = Pattern.compile("^vector\\((\\d+)\\)$");

    private static final String DIMENSION_SQL = """
            select format_type(a.atttypid, a.atttypmod)
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
            where c.relname = 'app_rag_chunk'
              and a.attname = 'embedding'
              and a.attnum > 0
              and not a.attisdropped
              and n.nspname = current_schema()
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RagEmbeddingService embeddingService;

    /** 解析一次即缓存：库产品与列定义在进程生命周期内不会变。 */
    private volatile Integer columnDimension;
    private volatile boolean resolved;

    public RagVectorIndex(JdbcTemplate jdbcTemplate, RagEmbeddingService embeddingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
    }

    /** 一条分片的向量，供内存降级路径与批量写入共用。 */
    public record ChunkVector(String id, List<Double> embedding) {
    }

    /** 是否走库内检索：仅 PostgreSQL 且 {@code app_rag_chunk.embedding} 存在（V15_2 已应用）。 */
    public boolean databaseRankingAvailable() {
        return dimension() != null;
    }

    /** 向量通道在检索轨迹里的自述——不能让 H2 降级路径在界面上冒充"库内向量检索"。 */
    public String describe(RagSettings settings) {
        String model = "%s/%s".formatted(settings.embeddingProvider(), settings.embeddingModel());
        Integer dimension = dimension();
        return dimension == null
                ? "JVM 内存余弦 · %s（%s 维）· PostgreSQL 形态下改走 pgvector".formatted(model, settings.embeddingDimension())
                : "pgvector 库内检索 · 余弦距离 <=> + HNSW 索引 · %s（%d 维）".formatted(model, dimension);
    }

    /**
     * 写入侧：把分片向量落进 pgvector 列。H2 上是 no-op（向量只存 embedding_json）。
     *
     * <p>批量写而不是每条一次往返：一篇 PDF 切几十上百片，逐条 update 会把入库时延
     * 全花在网络往返上。
     */
    public void store(List<ChunkVector> chunks) {
        if (chunks.isEmpty() || !databaseRankingAvailable()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(chunks.size());
        for (ChunkVector chunk : chunks) {
            ensureDimension(chunk.embedding().size());
            batch.add(new Object[]{literal(chunk.embedding()), chunk.id()});
        }
        jdbcTemplate.batchUpdate(
                "update app_rag_chunk set embedding = ? where id = ?",
                batch,
                new int[]{Types.OTHER, Types.VARCHAR});
    }

    private static final String SEARCH_SQL = """
            select id, 1 - (embedding <=> ?) as similarity
            from app_rag_chunk
            where organization_id = ?
              and embedding is not null
            order by embedding <=> ?
            limit ?
            """;

    /**
     * 库内检索：ORDER BY 余弦距离，走 HNSW 索引，只把 top-n 的 id 与分数取回 JVM。
     *
     * <p>取回后在 JVM 里按（相似度降序，id 升序）稳定重排：SQL 的 ORDER BY 只按距离，
     * 同分次序未定义，而降级路径必须给出同一顺序，否则对照测试没法断言。
     *
     * @return 相似度降序（同分按 id 升序）的候选，最多 n 条
     */
    public List<RagScoredId> rankInDatabase(String organizationId, List<Double> query, int n) {
        ensureDimension(query.size());
        String vector = literal(query);
        int limit = Math.max(1, n);
        List<RagScoredId> hits = jdbcTemplate.execute((Connection connection) -> {
            // HNSW 的候选表长度必须 >= limit，否则 pgvector 只会返回 ef_search 条——
            // candidateN 默认 50 已经超过默认值 40，不设就会静默少召回。
            // 无事务时 SET LOCAL 是空操作，因此按会话设置（连接池复用时同样生效，且只是个检索精度旋钮）。
            try (Statement statement = connection.createStatement()) {
                statement.execute("set hnsw.ef_search = " + Math.min(1000, Math.max(40, limit * 2)));
            }
            List<RagScoredId> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SEARCH_SQL)) {
                statement.setObject(1, vector, Types.OTHER);
                statement.setString(2, organizationId);
                statement.setObject(3, vector, Types.OTHER);
                statement.setInt(4, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new RagScoredId(resultSet.getString("id"), resultSet.getDouble("similarity")));
                    }
                }
            }
            return rows;
        });
        return hits == null ? List.of() : hits.stream()
                .filter(scored -> scored.score() > 0.0D)
                .sorted(Comparator.comparingDouble(RagScoredId::score).reversed()
                        .thenComparing(RagScoredId::id))
                .toList();
    }

    /** H2 降级：把该组织全部分片的向量读进 JVM 逐条算余弦（与库内路径同口径）。 */
    public List<RagScoredId> rankInMemory(List<ChunkVector> chunks, List<Double> query, int n) {
        return chunks.stream()
                .map(chunk -> new RagScoredId(chunk.id(),
                        Math.max(0.0D, embeddingService.cosine(query, chunk.embedding()))))
                .filter(scored -> scored.score() > 0.0D)
                .sorted(Comparator.comparingDouble(RagScoredId::score).reversed()
                        .thenComparing(RagScoredId::id))
                .limit(Math.max(1, n))
                .toList();
    }

    /** pgvector 的文本输入格式：{@code [0.1,0.2,...]}。 */
    static String literal(List<Double> vector) {
        StringBuilder builder = new StringBuilder(vector.size() * 8 + 2).append('[');
        for (int index = 0; index < vector.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector.get(index) == null ? 0.0D : vector.get(index).doubleValue());
        }
        return builder.append(']').toString();
    }

    private void ensureDimension(int actual) {
        Integer expected = dimension();
        if (expected == null || expected == actual) {
            return;
        }
        throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, """
                向量维度不匹配：app_rag_chunk.embedding 为 vector(%d)，当前 embedding 模型输出 %d 维。
                pgvector 检索已停止（不做截断比较——按前 %d 维比较两个不同语义空间的向量，\
                结果看着正常其实是噪声）。换 embedding 模型后需重建向量列与索引：
                  ALTER TABLE app_rag_chunk DROP COLUMN embedding;
                  ALTER TABLE app_rag_chunk ADD COLUMN embedding vector(%d);
                  CREATE INDEX app_rag_chunk_embedding_idx ON app_rag_chunk USING hnsw (embedding vector_cosine_ops);
                随后调用 POST /api/v1/rag/rebuild 重算全部分片向量。\
                """.formatted(expected, actual, Math.min(expected, actual), actual));
    }

    private Integer dimension() {
        if (!resolved) {
            synchronized (this) {
                if (!resolved) {
                    columnDimension = resolveDimension();
                    resolved = true;
                }
            }
        }
        return columnDimension;
    }

    private Integer resolveDimension() {
        if (!isPostgres()) {
            return null;
        }
        try {
            List<String> types = jdbcTemplate.queryForList(DIMENSION_SQL, String.class);
            if (types.isEmpty()) {
                // PostgreSQL 但没有向量列：db/postgresql 的 V15_2 未执行（例如手工建库时漏了
                // vendor 迁移目录）。此时降级到内存余弦，功能不受影响，只是没有库内检索。
                log.warn("PostgreSQL 上未找到 app_rag_chunk.embedding，向量检索回退到 JVM 内存余弦；"
                        + "确认 classpath:db/postgresql 的迁移已执行");
                return null;
            }
            Matcher matcher = VECTOR_TYPE.matcher(types.get(0).trim().toLowerCase(Locale.ROOT));
            if (!matcher.matches()) {
                log.warn("app_rag_chunk.embedding 的类型是 {}，不是 vector(n)，向量检索回退到 JVM 内存余弦",
                        types.get(0));
                return null;
            }
            return Integer.valueOf(matcher.group(1));
        } catch (DataAccessException exception) {
            log.warn("探测 pgvector 向量列失败，向量检索回退到 JVM 内存余弦: {}", exception.getMessage());
            return null;
        }
    }

    private boolean isPostgres() {
        try {
            Boolean postgres = jdbcTemplate.execute((Connection connection) -> {
                String product = connection.getMetaData().getDatabaseProductName();
                return product != null && product.toLowerCase(Locale.ROOT).contains("postgresql");
            });
            return Boolean.TRUE.equals(postgres);
        } catch (RuntimeException exception) {
            log.warn("探测数据库类型失败，向量检索回退到 JVM 内存余弦: {}", exception.getMessage());
            return false;
        }
    }
}

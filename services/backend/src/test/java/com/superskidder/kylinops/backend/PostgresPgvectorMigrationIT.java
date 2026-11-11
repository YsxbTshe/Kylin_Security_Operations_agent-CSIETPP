package com.superskidder.kylinops.backend;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Postgres + pgvector 上的迁移验收。
 *
 * <p>依赖外部数据库（{@code application-postgres-it.yml} 从 {@code POSTGRES_IT_JDBC_URL}
 * 取连接串），故按该环境变量门控：接入 failsafe 后 {@code mvn verify} 会执行 *IT，
 * 没有实例时应当跳过而不是让构建因缺外部依赖变红。
 */
@SpringBootTest
@ActiveProfiles("postgres-it")
@EnabledIfEnvironmentVariable(named = "POSTGRES_IT_JDBC_URL", matches = ".+")
class PostgresPgvectorMigrationIT {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyPostgresPgvectorMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).containsIgnoringCase("PostgreSQL");
        }

        String extension = jdbcTemplate.queryForObject(
                "select extname from pg_extension where extname = 'vector'",
                String.class
        );
        // V15_2 起向量列建在 app_rag_chunk（检索的最小单元是 chunk）；
        // V15_1 建在 app_rag_document 上的同名列没有任何读取方，已随 V15_2 删除。
        String chunkColumnType = jdbcTemplate.queryForObject("""
                select format_type(a.atttypid, a.atttypmod)
                from pg_attribute a
                join pg_class c on c.oid = a.attrelid
                where c.relname = 'app_rag_chunk' and a.attname = 'embedding'
                  and a.attnum > 0 and not a.attisdropped
                """, String.class);
        Integer documentColumnCount = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_name = 'app_rag_document'
                  and column_name = 'embedding'
                """, Integer.class);
        String indexDefinition = jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where indexname = 'app_rag_chunk_embedding_idx'",
                String.class);

        assertThat(extension).isEqualTo("vector");
        assertThat(chunkColumnType).isEqualTo("vector(384)");
        assertThat(documentColumnCount).isZero();
        // 余弦算子类必须与 RagVectorIndex 用的 <=> 对齐，否则索引会被规划器忽略
        assertThat(indexDefinition).contains("hnsw").contains("vector_cosine_ops");
    }
}

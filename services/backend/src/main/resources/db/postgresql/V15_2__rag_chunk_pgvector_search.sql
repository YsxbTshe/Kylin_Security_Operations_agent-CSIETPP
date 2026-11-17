-- pgvector 实装：向量通道从「JVM 内存逐条余弦」切到库内检索（PostgreSQL 形态）。
-- 与 V15_1 一样只在 PostgreSQL 上执行（FlywayConfig#resolvedLocations 仅在 PG 追加
-- classpath:db/postgresql），H2 开发/测试库不受影响，仍走内存余弦降级路径。
--
-- 为什么列建在 app_rag_chunk 而不是 app_rag_document：
-- 检索的最小单元是 chunk（RagService 的向量通道对 chunk 打分、RRF 也在 chunk 粒度融合），
-- 文档级向量没有任何读取方。V15_1 建在 app_rag_document 上的 embedding 列正因如此
-- 从建成起就是死列（全仓无读无写），本迁移把它一并删掉，避免"看着像向量库、其实没人用"。
create extension if not exists vector;

alter table app_rag_chunk
    add column if not exists embedding vector(384);

-- 距离算子必须与 JVM 降级路径的打分口径一致：两边都是余弦，才谈得上
-- "同一查询在 PG 与 H2 下 top-k 可比"（对照见 RagPgVectorParityIT）。
-- 因此索引用 vector_cosine_ops，查询用 <=>（余弦距离），相似度 = 1 - 距离。
--
-- 选 HNSW 不选 ivfflat：ivfflat 的倒排列表要靠建索引时的数据训练，在空表上建出来的
-- 索引没有可用聚类，小语料下召回会莫名其妙地掉；HNSW 是增量构建的，建在空表上也正确。
-- 需要 pgvector >= 0.5.0（部署用的 pgvector/pgvector:pg16 为 0.8.x）。
create index if not exists app_rag_chunk_embedding_idx
    on app_rag_chunk using hnsw (embedding vector_cosine_ops);

-- 维度固定 384，与 app_rag_config.embedding_dimension 的出厂值一致。
-- 换用别的 embedding 模型（维度不同）时，SQL 路径会直接报维度不匹配而**不是**
-- 静默按短向量截断比较；按 RagVectorIndex 抛出的指引重建列与索引后
-- 调 POST /api/v1/rag/rebuild 重算全部分片向量即可。
drop index if exists app_rag_document_embedding_idx;

alter table app_rag_document
    drop column if exists embedding;

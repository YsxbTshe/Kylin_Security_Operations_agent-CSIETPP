package com.superskidder.kylinops.backend.rag;

import com.superskidder.kylinops.backend.common.AppException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RagSettingsService {

    private static final String DEFAULT_ID = "default";

    private final JdbcTemplate jdbcTemplate;

    public RagSettingsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RagSettings current() {
        List<RagSettings> rows = jdbcTemplate.query("""
                select id, embedding_provider, embedding_base_url, embedding_api_key, embedding_model,
                       embedding_dimension, chunk_size, chunk_overlap, top_k,
                       milvus_enabled, milvus_base_url, milvus_collection,
                       elasticsearch_enabled, elasticsearch_base_url, elasticsearch_index,
                       redis_enabled, redis_base_url, redis_prefix,
                       reranker_enabled, reranker_base_url, reranker_api_key, reranker_model, reranker_top_n,
                       vision_config_id,
                       updated_by, updated_at
                from app_rag_config
                where id = ?
                """, (resultSet, rowNum) -> new RagSettings(
                resultSet.getString("id"),
                resultSet.getString("embedding_provider"),
                resultSet.getString("embedding_base_url"),
                resultSet.getString("embedding_api_key"),
                resultSet.getString("embedding_model"),
                resultSet.getInt("embedding_dimension"),
                resultSet.getInt("chunk_size"),
                resultSet.getInt("chunk_overlap"),
                resultSet.getInt("top_k"),
                resultSet.getBoolean("milvus_enabled"),
                resultSet.getString("milvus_base_url"),
                resultSet.getString("milvus_collection"),
                resultSet.getBoolean("elasticsearch_enabled"),
                resultSet.getString("elasticsearch_base_url"),
                resultSet.getString("elasticsearch_index"),
                resultSet.getBoolean("redis_enabled"),
                resultSet.getString("redis_base_url"),
                resultSet.getString("redis_prefix"),
                resultSet.getBoolean("reranker_enabled"),
                resultSet.getString("reranker_base_url"),
                resultSet.getString("reranker_api_key"),
                resultSet.getString("reranker_model"),
                resultSet.getInt("reranker_top_n"),
                resultSet.getString("vision_config_id"),
                resultSet.getString("updated_by"),
                resultSet.getTimestamp("updated_at").toLocalDateTime()
        ), DEFAULT_ID);
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "RAG 配置缺失");
        }
        return rows.get(0);
    }

    public RagSettingsView currentView() {
        return toView(current());
    }

    public RagSettingsView update(String operator, UpdateRagSettingsRequest request) {
        RagSettings current = current();
        String provider = normalize(request.embeddingProvider(), current.embeddingProvider()).toUpperCase(Locale.ROOT);
        if (!provider.equals("LOCAL") && !provider.equals("OPENAI_COMPATIBLE")) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Embedding 提供商只支持 LOCAL 或 OPENAI_COMPATIBLE");
        }
        String baseUrl = normalize(request.embeddingBaseUrl(), current.embeddingBaseUrl());
        String apiKey = request.embeddingApiKey() == null || request.embeddingApiKey().isBlank()
                ? current.embeddingApiKey()
                : request.embeddingApiKey().trim();
        String model = normalize(request.embeddingModel(), current.embeddingModel());
        if (provider.equals("OPENAI_COMPATIBLE") && baseUrl.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "OpenAI compatible Embedding 必须填写接口地址");
        }
        if (request.chunkOverlap() >= request.chunkSize()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "分片重叠必须小于分片长度");
        }

        // 重排参数全部可选：老版本前端表单不带这些字段时保持当前配置不变
        boolean rerankerEnabled = request.rerankerEnabled() == null
                ? current.rerankerEnabled() : request.rerankerEnabled();
        String rerankerBaseUrl = normalize(request.rerankerBaseUrl(), current.rerankerBaseUrl());
        String rerankerApiKey = request.rerankerApiKey() == null || request.rerankerApiKey().isBlank()
                ? current.rerankerApiKey()
                : request.rerankerApiKey().trim();
        String rerankerModel = normalize(request.rerankerModel(), current.rerankerModel());
        int rerankerTopN = request.rerankerTopN() == null ? current.rerankerTopN() : request.rerankerTopN();
        String visionConfigId = normalize(request.visionConfigId(), current.visionConfigId());
        if (rerankerEnabled && rerankerModel.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "启用重排时必须填写重排模型");
        }

        jdbcTemplate.update("""
                update app_rag_config
                set embedding_provider = ?, embedding_base_url = ?, embedding_api_key = ?, embedding_model = ?,
                    embedding_dimension = ?, chunk_size = ?, chunk_overlap = ?, top_k = ?,
                    milvus_enabled = ?, milvus_base_url = ?, milvus_collection = ?,
                    elasticsearch_enabled = ?, elasticsearch_base_url = ?, elasticsearch_index = ?,
                    redis_enabled = ?, redis_base_url = ?, redis_prefix = ?,
                    reranker_enabled = ?, reranker_base_url = ?, reranker_api_key = ?,
                    reranker_model = ?, reranker_top_n = ?, vision_config_id = ?,
                    updated_by = ?, updated_at = ?
                where id = ?
                """,
                provider,
                baseUrl,
                apiKey == null ? "" : apiKey,
                model,
                request.embeddingDimension(),
                request.chunkSize(),
                request.chunkOverlap(),
                request.topK(),
                request.milvusEnabled(),
                request.milvusBaseUrl().trim(),
                request.milvusCollection().trim(),
                request.elasticsearchEnabled(),
                request.elasticsearchBaseUrl().trim(),
                request.elasticsearchIndex().trim(),
                request.redisEnabled(),
                request.redisBaseUrl().trim(),
                request.redisPrefix().trim(),
                rerankerEnabled,
                rerankerBaseUrl,
                rerankerApiKey == null ? "" : rerankerApiKey,
                rerankerModel,
                rerankerTopN,
                visionConfigId,
                operator,
                Timestamp.valueOf(LocalDateTime.now()),
                DEFAULT_ID
        );
        return currentView();
    }

    public RagSettingsTestResponse test() {
        RagSettings settings = current();
        if (settings.embeddingProvider().equals("OPENAI_COMPATIBLE")
                && (settings.embeddingBaseUrl().isBlank() || settings.embeddingApiKey().isBlank())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Embedding 接口地址和 API Key 不能为空");
        }
        return new RagSettingsTestResponse(true, "RAG 配置可用");
    }

    private RagSettingsView toView(RagSettings settings) {
        return new RagSettingsView(
                settings.embeddingProvider(),
                settings.embeddingBaseUrl(),
                settings.embeddingModel(),
                settings.embeddingDimension(),
                settings.chunkSize(),
                settings.chunkOverlap(),
                settings.topK(),
                settings.embeddingApiKey() != null && !settings.embeddingApiKey().isBlank(),
                mask(settings.embeddingApiKey()),
                settings.milvusEnabled(),
                settings.milvusBaseUrl(),
                settings.milvusCollection(),
                settings.elasticsearchEnabled(),
                settings.elasticsearchBaseUrl(),
                settings.elasticsearchIndex(),
                settings.redisEnabled(),
                settings.redisBaseUrl(),
                settings.redisPrefix(),
                settings.rerankerEnabled(),
                settings.rerankerBaseUrl(),
                settings.rerankerModel(),
                settings.rerankerTopN(),
                settings.rerankerApiKey() != null && !settings.rerankerApiKey().isBlank(),
                mask(settings.rerankerApiKey()),
                settings.visionConfigId(),
                settings.updatedBy(),
                settings.updatedAt() == null ? "" : settings.updatedAt().toString().replace('T', ' ')
        );
    }

    private String normalize(String value, String fallback) {
        if (value == null) {
            return fallback == null ? "" : fallback.trim();
        }
        return value.trim();
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= 8) {
            return "********";
        }
        return normalized.substring(0, 4) + "********" + normalized.substring(normalized.length() - 4);
    }
}

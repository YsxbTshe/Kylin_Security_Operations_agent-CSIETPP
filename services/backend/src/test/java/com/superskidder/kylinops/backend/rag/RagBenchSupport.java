package com.superskidder.kylinops.backend.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared helpers for RAG smoke/benchmark runners: model config seeding, corpus + env loading. */
public final class RagBenchSupport {

    private RagBenchSupport() {
    }

    /** Locate a file by trying the path relative to several candidate roots (test CWD is services/backend). */
    public static Path resolve(String relative) {
        String[] roots = {".", "..", "../..", "../../.."};
        for (String root : roots) {
            Path candidate = Path.of(root, relative).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate " + relative + " from CWD " + Path.of(".").toAbsolutePath());
    }

    public static Path corpusFile(String relativeInCorpus) {
        return resolve("rag-corpus/" + relativeInCorpus);
    }

    public static Path corpusRoot() {
        return resolve("rag-corpus");
    }

    public static Map<String, String> loadModelsEnv() throws IOException {
        Path env = resolve(".secrets/models.env");
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(env)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return values;
    }

    /** Point RagSettings at the real embedding endpoint + Elasticsearch, and enable the chat model. */
    public static void seedModelConfig(JdbcTemplate jdbc, Map<String, String> env, String esIndex) {
        String baseUrl = env.getOrDefault("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1");
        String apiKey = env.get("SILICONFLOW_API_KEY");
        String embedModel = env.getOrDefault("RAG_EMBEDDING_MODEL", "BAAI/bge-m3");
        int embedDim = Integer.parseInt(env.getOrDefault("RAG_EMBEDDING_DIM", "1024"));
        String chatModel = env.getOrDefault("LLM_CHAT_MODEL", "Qwen/Qwen2.5-72B-Instruct");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        jdbc.update("""
                update app_rag_config set embedding_provider = 'OPENAI_COMPATIBLE', embedding_base_url = ?,
                    embedding_api_key = ?, embedding_model = ?, embedding_dimension = ?,
                    chunk_size = 700, chunk_overlap = 120, top_k = 8,
                    milvus_enabled = false, redis_enabled = false,
                    elasticsearch_enabled = true, elasticsearch_base_url = 'http://localhost:9200',
                    elasticsearch_index = ?, updated_by = 'bench', updated_at = ?
                where id = 'default'
                """, baseUrl, apiKey, embedModel, embedDim, esIndex, now);

        jdbc.update("delete from app_platform_llm_config");
        jdbc.update("""
                insert into app_platform_llm_config (id, provider_type, group_name, base_url, api_key, model,
                    enabled, timeout_ms, retry_count, updated_by, updated_at)
                values ('llm-bench', 'OPENAI_COMPATIBLE', '默认', ?, ?, ?, true, 15000, 0, 'bench', ?)
                """, baseUrl, apiKey, chatModel, now);
    }

    public static String stripFrontMatter(String text) {
        String normalized = text.stripLeading();
        if (normalized.startsWith("---")) {
            int end = normalized.indexOf("\n---", 3);
            if (end > 0) {
                int after = normalized.indexOf('\n', end + 1);
                return after > 0 ? normalized.substring(after + 1).stripLeading() : normalized;
            }
        }
        return text;
    }
}

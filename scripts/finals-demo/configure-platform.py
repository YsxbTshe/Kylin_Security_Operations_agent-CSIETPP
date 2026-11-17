#!/usr/bin/env python3
"""Idempotently configure the finals backend with real model and RAG services."""

from __future__ import annotations

from demo_lib import DemoApi, REPO_ROOT, data, load_env, require


def llm_payload(env: dict[str, str], model: str, enabled: bool) -> dict[str, object]:
    return {
        "providerType": "OPENAI_COMPATIBLE",
        "groupName": "决赛演示",
        "baseUrl": env["SILICONFLOW_BASE_URL"],
        "apiKey": env["SILICONFLOW_API_KEY"],
        "model": model,
        "enabled": enabled,
        "timeoutMs": 120000,
        "retryCount": 1,
        "thinkingLevels": "OFF",
        "thinkingDefault": "OFF",
    }


def upsert_model(api: DemoApi, env: dict[str, str], model: str, enabled: bool) -> str:
    configs = data(api.request("/api/v1/platform/llm-config"))
    match = next((item for item in configs if item["model"] == model), None)
    payload = llm_payload(env, model, enabled)
    if match:
        result = data(api.request(f"/api/v1/platform/llm-config/{match['id']}", "PUT", payload))
        return result["id"]
    result = data(api.request("/api/v1/platform/llm-config", "POST", payload))
    return result["id"]


def main() -> int:
    env = load_env()
    require(
        env,
        "SILICONFLOW_API_KEY",
        "SILICONFLOW_BASE_URL",
        "SILICONFLOW_CHAT_MODEL",
        "SILICONFLOW_CHAT_MODEL_PRO",
        "SILICONFLOW_EMBEDDING_MODEL",
        "SILICONFLOW_EMBEDDING_DIMENSION",
        "SILICONFLOW_RERANK_MODEL",
        "DEMO_BASE_URL",
        "DEMO_ES_URL",
        "DEMO_ADMIN_USERNAME",
        "DEMO_ADMIN_PASSWORD",
    )
    if env["SILICONFLOW_BASE_URL"].rstrip("/").endswith(
        ("/chat/completions", "/embeddings", "/rerank")
    ):
        raise RuntimeError("SILICONFLOW_BASE_URL 必须填 https://api.siliconflow.cn/v1，不要带具体端点")

    api = DemoApi(env["DEMO_BASE_URL"])
    token = api.login(env["DEMO_ADMIN_USERNAME"], env["DEMO_ADMIN_PASSWORD"])
    scrape_token = REPO_ROOT / ".secrets" / "backend-scrape-token"
    scrape_token.write_text(token + "\n", encoding="utf-8")
    scrape_token.chmod(0o600)

    flash_id = upsert_model(api, env, env["SILICONFLOW_CHAT_MODEL"], True)
    flash_test = data(api.request(f"/api/v1/platform/llm-config/{flash_id}/test", "POST"))
    print(f"LLM Flash: {flash_test}")

    pro_id = upsert_model(api, env, env["SILICONFLOW_CHAT_MODEL_PRO"], True)
    try:
        pro_test = data(api.request(f"/api/v1/platform/llm-config/{pro_id}/test", "POST"))
        print(f"LLM Pro: {pro_test}")
    finally:
        api.request(
            f"/api/v1/platform/llm-config/{pro_id}",
            "PUT",
            llm_payload(env, env["SILICONFLOW_CHAT_MODEL_PRO"], False),
        )
        api.request(
            f"/api/v1/platform/llm-config/{flash_id}",
            "PUT",
            llm_payload(env, env["SILICONFLOW_CHAT_MODEL"], True),
        )

    rag_payload = {
        "embeddingProvider": "OPENAI_COMPATIBLE",
        "embeddingBaseUrl": env["SILICONFLOW_BASE_URL"],
        "embeddingApiKey": env["SILICONFLOW_API_KEY"],
        "embeddingModel": env["SILICONFLOW_EMBEDDING_MODEL"],
        "embeddingDimension": int(env["SILICONFLOW_EMBEDDING_DIMENSION"]),
        "chunkSize": 800,
        "chunkOverlap": 120,
        "topK": 5,
        "milvusEnabled": False,
        "milvusBaseUrl": "http://127.0.0.1:19530",
        "milvusCollection": "kylinops_rag_chunks",
        "elasticsearchEnabled": True,
        "elasticsearchBaseUrl": env["DEMO_ES_URL"],
        "elasticsearchIndex": "kylinops-rag-chunks",
        "redisEnabled": False,
        "redisBaseUrl": "127.0.0.1:6379",
        "redisPrefix": "kylinops-finals",
        "rerankerEnabled": True,
        "rerankerBaseUrl": env["SILICONFLOW_BASE_URL"],
        "rerankerApiKey": env["SILICONFLOW_API_KEY"],
        "rerankerModel": env["SILICONFLOW_RERANK_MODEL"],
        "rerankerTopN": 20,
        "visionConfigId": "",
    }
    settings = data(api.request("/api/v1/rag/settings", "PUT", rag_payload))
    print(
        "RAG:",
        settings["embeddingProvider"],
        settings["embeddingModel"],
        f"{settings['embeddingDimension']}d",
        "ES=" + str(settings["elasticsearchEnabled"]),
        "rerank=" + str(settings["rerankerEnabled"]),
    )
    print("平台真实依赖配置完成；录制默认仅启用 Flash，Pro 保留为手工切换。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

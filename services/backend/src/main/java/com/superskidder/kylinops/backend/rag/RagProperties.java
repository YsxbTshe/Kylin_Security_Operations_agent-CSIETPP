package com.superskidder.kylinops.backend.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    private boolean milvusEnabled;
    private String milvusBaseUrl = "http://localhost:19530";
    private String milvusCollection = "kylinops_rag_chunks";
    private boolean elasticsearchEnabled;
    private String elasticsearchBaseUrl = "http://localhost:9200";
    private String elasticsearchIndex = "kylinops-rag-chunks";
    private boolean redisEnabled;
    private String redisBaseUrl = "localhost:6379";
    private String redisPrefix = "kylinops";

    public boolean isMilvusEnabled() {
        return milvusEnabled;
    }

    public void setMilvusEnabled(boolean milvusEnabled) {
        this.milvusEnabled = milvusEnabled;
    }

    public String getMilvusBaseUrl() {
        return milvusBaseUrl;
    }

    public void setMilvusBaseUrl(String milvusBaseUrl) {
        this.milvusBaseUrl = milvusBaseUrl;
    }

    public String getMilvusCollection() {
        return milvusCollection;
    }

    public void setMilvusCollection(String milvusCollection) {
        this.milvusCollection = milvusCollection;
    }

    public boolean isElasticsearchEnabled() {
        return elasticsearchEnabled;
    }

    public void setElasticsearchEnabled(boolean elasticsearchEnabled) {
        this.elasticsearchEnabled = elasticsearchEnabled;
    }

    public String getElasticsearchBaseUrl() {
        return elasticsearchBaseUrl;
    }

    public void setElasticsearchBaseUrl(String elasticsearchBaseUrl) {
        this.elasticsearchBaseUrl = elasticsearchBaseUrl;
    }

    public String getElasticsearchIndex() {
        return elasticsearchIndex;
    }

    public void setElasticsearchIndex(String elasticsearchIndex) {
        this.elasticsearchIndex = elasticsearchIndex;
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public String getRedisBaseUrl() {
        return redisBaseUrl;
    }

    public void setRedisBaseUrl(String redisBaseUrl) {
        this.redisBaseUrl = redisBaseUrl;
    }

    public String getRedisPrefix() {
        return redisPrefix;
    }

    public void setRedisPrefix(String redisPrefix) {
        this.redisPrefix = redisPrefix;
    }
}

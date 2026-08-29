package com.fitpilot.rag.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fitpilot.rag")
public class RagProperties {
    private boolean enabled = true;
    private final Elasticsearch elasticsearch = new Elasticsearch();
    private final Embedding embedding = new Embedding();
    private final Chunking chunking = new Chunking();
    private final Retrieval retrieval = new Retrieval();
    private final Indexing indexing = new Indexing();

    @PostConstruct
    void validate() {
        if (embedding.dimensions != 384) {
            throw new IllegalStateException("FitPilot V3 schema requires EMBEDDING_DIMENSIONS=384");
        }
        if (chunking.childOverlapChars >= chunking.childMaxChars) {
            throw new IllegalStateException("RAG child overlap must be smaller than child size");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Elasticsearch getElasticsearch() { return elasticsearch; }
    public Embedding getEmbedding() { return embedding; }
    public Chunking getChunking() { return chunking; }
    public Retrieval getRetrieval() { return retrieval; }
    public Indexing getIndexing() { return indexing; }

    public static class Elasticsearch {
        private String url = "http://localhost:9200";
        private String index = "fitpilot-knowledge-v1";
        private int connectTimeoutMs = 1000;
        private int requestTimeoutMs = 3000;
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getIndex() { return index; }
        public void setIndex(String index) { this.index = index; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    }

    public static class Embedding {
        private String provider = "LOCAL";
        private int dimensions = 384;
        private String apiUrl = "";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        private int timeoutSeconds = 10;
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Chunking {
        private int parentMaxChars = 2400;
        private int childMaxChars = 700;
        private int childOverlapChars = 100;
        public int getParentMaxChars() { return parentMaxChars; }
        public void setParentMaxChars(int parentMaxChars) { this.parentMaxChars = parentMaxChars; }
        public int getChildMaxChars() { return childMaxChars; }
        public void setChildMaxChars(int childMaxChars) { this.childMaxChars = childMaxChars; }
        public int getChildOverlapChars() { return childOverlapChars; }
        public void setChildOverlapChars(int childOverlapChars) { this.childOverlapChars = childOverlapChars; }
    }

    public static class Retrieval {
        private int candidateLimit = 30;
        private int rrfK = 60;
        private int maxTopK = 20;
        public int getCandidateLimit() { return candidateLimit; }
        public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
        public int getMaxTopK() { return maxTopK; }
        public void setMaxTopK(int maxTopK) { this.maxTopK = maxTopK; }
    }

    public static class Indexing {
        private long fixedDelayMs = 5000;
        private int retryBatchSize = 20;
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
        public int getRetryBatchSize() { return retryBatchSize; }
        public void setRetryBatchSize(int retryBatchSize) { this.retryBatchSize = retryBatchSize; }
    }
}

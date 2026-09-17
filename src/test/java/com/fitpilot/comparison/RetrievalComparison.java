package com.fitpilot.comparison;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.evaluation.application.EvaluationDatasetLoader;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.domain.RagTuningProfile;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Frozen diagnostic queries, not a real-embedding or independently labelled quality benchmark. */
final class RetrievalComparison {
    private final HybridRetrievalService retrieval;
    private final KnowledgeIngestionService ingestion;
    private final EvaluationDatasetLoader datasets;
    private final RagProperties properties;

    RetrievalComparison(HybridRetrievalService retrieval, KnowledgeIngestionService ingestion,
                        EvaluationDatasetLoader datasets, RagProperties properties) {
        this.retrieval = retrieval; this.ingestion = ingestion; this.datasets = datasets; this.properties = properties;
    }

    void run() throws Exception {
        for (var document : datasets.ragCorpus()) assertThat(ingestion.ingest(document).indexStatus()).isEqualTo("INDEXED");
        Query[] queries;
        try (var input = new ClassPathResource("comparison/rag-queries.json").getInputStream()) {
            queries = new ObjectMapper().readValue(input, Query[].class);
        }
        var chunk = properties.getChunking(); int k = properties.getRetrieval().getRrfK();
        var profiles = List.of(
                new RagTuningProfile("bm25-only", chunk.getParentMaxChars(), chunk.getChildMaxChars(), chunk.getChildOverlapChars(), k, 1, 0, "NONE"),
                new RagTuningProfile("vector-only", chunk.getParentMaxChars(), chunk.getChildMaxChars(), chunk.getChildOverlapChars(), k, 0, 1, "NONE"),
                new RagTuningProfile("hybrid-rrf", chunk.getParentMaxChars(), chunk.getChildMaxChars(), chunk.getChildOverlapChars(), k, 1, 1, "NONE"),
                new RagTuningProfile("hybrid-rerank", chunk.getParentMaxChars(), chunk.getChildMaxChars(), chunk.getChildOverlapChars(), k, 1, 1, "TERM_OVERLAP_TRUST"));
        List<Map<String, Object>> rows = new ArrayList<>();
        // Warm every profile on the same queries; do not alter labels/settings after seeing results.
        for (var profile : profiles) for (var query : queries) retrieval.searchOffline(query.query(), 5, "EVALUATION", profile);
        for (int round = 0; round < 3; round++) {
            for (int offset = 0; offset < profiles.size(); offset++) {
                var profile = profiles.get((round + offset) % profiles.size());
                for (var query : queries) {
                    long start = System.nanoTime();
                    var response = retrieval.searchOffline(query.query(), 5, "EVALUATION", profile);
                    double ms = ComparisonEvidence.elapsed(start);
                    var urls = response.contexts().stream().map(c -> c.citation().sourceUrl()).distinct().toList();
                    int index = query.source().isEmpty() ? -1 : urls.indexOf("https://fitpilot.local/eval/" + query.source());
                    var row = new LinkedHashMap<String, Object>();
                    row.put("round", round + 1); row.put("profile", profile.id()); row.put("case", query.id());
                    row.put("category", query.category()); row.put("answerable", !query.source().isEmpty());
                    row.put("hitAt5", index >= 0); row.put("reciprocalRank", index < 0 ? 0 : 1.0 / (index + 1));
                    row.put("returnedSources", urls); row.put("latencyMs", ms);
                    row.put("embedding", response.embeddingProvider()); row.put("actualMode", response.retrievalMode());
                    rows.add(row);
                }
            }
        }
        ComparisonEvidence.write("retrieval", Map.of("rows", rows, "profiles", profiles,
                "documents", datasets.ragCorpus().size(), "queries", queries.length,
                "scope", "local deterministic hashing embedding; author-labelled diagnostic cases; no external model calls or human citation-support evaluation"));
    }

    record Query(String id, String category, String query, String source) {}
}

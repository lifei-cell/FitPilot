package com.fitpilot.rag.application;

import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.domain.RagTuningProfile;
import org.springframework.stereotype.Service;

import java.util.List;

/** Defines one-factor offline variants around the current live RAG configuration. */
@Service
public class RagExperimentCatalog {
    private final RagProperties properties;

    public RagExperimentCatalog(RagProperties properties) {
        this.properties = properties;
    }

    public List<RagTuningProfile> profiles() {
        RagProperties.Chunking chunking = properties.getChunking();
        RagProperties.Retrieval retrieval = properties.getRetrieval();
        int parent = chunking.getParentMaxChars();
        int child = chunking.getChildMaxChars();
        int overlap = chunking.getChildOverlapChars();
        int smallChild = Math.max(100, child * 2 / 3);
        int largeChild = child * 4 / 3;
        return List.of(
                profile("baseline", parent, child, overlap, retrieval.getRrfK(), 1, 1,
                        "TERM_OVERLAP_TRUST"),
                profile("chunk-small", Math.max(smallChild, parent * 3 / 4), smallChild,
                        Math.min(smallChild - 1, overlap * 2 / 3), retrieval.getRrfK(), 1, 1,
                        "TERM_OVERLAP_TRUST"),
                profile("chunk-large", Math.max(largeChild, parent * 4 / 3), largeChild,
                        Math.min(largeChild - 1, overlap * 4 / 3), retrieval.getRrfK(), 1, 1,
                        "TERM_OVERLAP_TRUST"),
                profile("rrf-bm25-heavy", parent, child, overlap, retrieval.getRrfK(), 1.5, .5,
                        "TERM_OVERLAP_TRUST"),
                profile("rrf-vector-heavy", parent, child, overlap, retrieval.getRrfK(), .5, 1.5,
                        "TERM_OVERLAP_TRUST"),
                profile("rerank-overlap", parent, child, overlap, retrieval.getRrfK(), 1, 1,
                        "TERM_OVERLAP"),
                profile("rerank-none", parent, child, overlap, retrieval.getRrfK(), 1, 1, "NONE"));
    }

    private RagTuningProfile profile(String id, int parent, int child, int overlap, int rrfK,
                                     double bm25Weight, double vectorWeight, String rerank) {
        return new RagTuningProfile(id, parent, child, overlap, rrfK, bm25Weight, vectorWeight, rerank);
    }
}

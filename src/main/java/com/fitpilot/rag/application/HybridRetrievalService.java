package com.fitpilot.rag.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.domain.KnowledgeModels;
import com.fitpilot.rag.dto.RagDtos;
import com.fitpilot.rag.embedding.EmbeddingProvider;
import com.fitpilot.rag.infrastructure.ElasticsearchKnowledgeIndex;
import com.fitpilot.rag.infrastructure.KnowledgeRepository;
import com.fitpilot.rag.infrastructure.RagGovernanceRepository;
import com.fitpilot.rag.processing.TextAnalyzer;
import com.fitpilot.observability.FitPilotMetrics;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@ConditionalOnProperty(prefix = "fitpilot.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HybridRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);
    private final KnowledgeRepository repository;
    private final ElasticsearchKnowledgeIndex lexicalIndex;
    private final EmbeddingProvider embeddings;
    private final TextAnalyzer analyzer;
    private final RagProperties properties;
    private final FitPilotMetrics metrics;
    private final RagGovernanceRepository governance;

    public HybridRetrievalService(KnowledgeRepository repository, ElasticsearchKnowledgeIndex lexicalIndex,
                                  EmbeddingProvider embeddings, TextAnalyzer analyzer, RagProperties properties,
                                  FitPilotMetrics metrics, RagGovernanceRepository governance) {
        this.repository = repository;
        this.lexicalIndex = lexicalIndex;
        this.embeddings = embeddings;
        this.analyzer = analyzer;
        this.properties = properties;
        this.metrics = metrics;
        this.governance = governance;
    }

    @Observed(name = "fitpilot.rag.search")
    public RagDtos.SearchResponse search(String query, int requestedTopK, String category) {
        return search(null, query, requestedTopK, category);
    }

    public RagDtos.SearchResponse search(Long userId, String query, int requestedTopK, String category) {
        long started = System.nanoTime();
        int topK = Math.max(1, Math.min(requestedTopK, properties.getRetrieval().getMaxTopK()));
        int candidateLimit = Math.max(topK, properties.getRetrieval().getCandidateLimit());
        String normalizedCategory = category == null || category.isBlank() ? null : category.trim();
        String lexicalQuery = analyzer.lexicalText(query);
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        boolean lexicalSucceeded = collectLexical(lexicalQuery, normalizedCategory, candidateLimit, candidates);
        boolean vectorSucceeded = collectVector(query, normalizedCategory, candidateLimit, candidates);
        if (!lexicalSucceeded && !vectorSucceeded) {
            metrics.rag("UNAVAILABLE", "FAILED", elapsedMillis(started));
            throw new BusinessException(ErrorCode.RAG_RETRIEVAL_UNAVAILABLE,
                    "knowledge retrieval services are unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }

        List<UUID> ids = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed())
                .map(Candidate::id).toList();
        Map<UUID, KnowledgeModels.ChunkContext> contexts = repository.contexts(ids);
        Set<String> queryTerms = new LinkedHashSet<>(analyzer.tokens(query));
        Map<UUID, RankedContext> parents = new LinkedHashMap<>();
        for (UUID id : ids) {
            Candidate candidate = candidates.get(id);
            KnowledgeModels.ChunkContext context = contexts.get(id);
            if (context == null) continue;
            double score = rerank(query, queryTerms, candidate.rrfScore(), context);
            RankedContext ranked = new RankedContext(candidate, context, score);
            parents.merge(context.parentChunkId(), ranked, this::better);
        }
        List<RagDtos.RetrievedContext> results = parents.values().stream()
                .sorted(Comparator.comparingDouble(RankedContext::score).reversed())
                .limit(topK).map(this::toDto).toList();
        String mode = lexicalSucceeded && vectorSucceeded ? "HYBRID_RRF"
                : lexicalSucceeded ? "BM25_ONLY" : "VECTOR_ONLY";
        UUID retrievalId = governance.saveRetrieval(userId, hash(query), query, normalizedCategory, mode, results);
        metrics.rag(mode, "SUCCEEDED", elapsedMillis(started));
        return new RagDtos.SearchResponse(retrievalId, query, mode, embeddings.name(), candidates.size(), results);
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private boolean collectLexical(String query, String category, int limit, Map<UUID, Candidate> candidates) {
        try {
            List<KnowledgeModels.SearchHit> hits = lexicalIndex.search(query, category, limit);
            for (int i = 0; i < hits.size(); i++) {
                Candidate candidate = candidates.computeIfAbsent(hits.get(i).chunkId(), Candidate::new);
                candidate.add("BM25", reciprocalRank(i + 1));
            }
            return true;
        } catch (RuntimeException failure) {
            log.warn("BM25 retrieval unavailable, degrading to vector search: {}", failure.getMessage());
            return false;
        }
    }

    private boolean collectVector(String query, String category, int limit, Map<UUID, Candidate> candidates) {
        try {
            List<KnowledgeModels.SearchHit> hits = repository.vectorSearch(embeddings.embed(query), category, limit);
            for (int i = 0; i < hits.size(); i++) {
                Candidate candidate = candidates.computeIfAbsent(hits.get(i).chunkId(), Candidate::new);
                candidate.add("VECTOR", reciprocalRank(i + 1));
            }
            return true;
        } catch (RuntimeException failure) {
            log.warn("Vector retrieval unavailable, degrading to BM25: {}", failure.getMessage());
            return false;
        }
    }

    private double reciprocalRank(int rank) {
        return 1.0 / (properties.getRetrieval().getRrfK() + rank);
    }

    private double rerank(String query, Set<String> queryTerms, double rrf,
                          KnowledgeModels.ChunkContext context) {
        Set<String> candidateTerms = new LinkedHashSet<>(analyzer.tokens(
                context.title() + " " + context.heading() + " " + context.childContent()));
        long matches = queryTerms.stream().filter(candidateTerms::contains).count();
        double overlap = queryTerms.isEmpty() ? 0 : (double) matches / queryTerms.size();
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        String candidateText = (context.title() + " " + context.heading() + " " + context.childContent())
                .toLowerCase(Locale.ROOT);
        double phraseBoost = normalizedQuery.length() >= 2 && candidateText.contains(normalizedQuery) ? 0.15 : 0;
        return rrf * 10 + overlap * 0.35 + phraseBoost + trustWeight(context.trustLevel()) * 0.04;
    }

    private RankedContext better(RankedContext left, RankedContext right) {
        if (right.score() > left.score()) {
            right.candidate().sources.addAll(left.candidate().sources);
            return right;
        }
        left.candidate().sources.addAll(right.candidate().sources);
        return left;
    }

    private RagDtos.RetrievedContext toDto(RankedContext ranked) {
        KnowledgeModels.ChunkContext context = ranked.context();
        return new RagDtos.RetrievedContext(context.documentId(), context.chunkId(), context.title(),
                context.category(), context.heading(), context.parentContent(), round(ranked.score()),
                new RagDtos.Citation(context.documentId(), context.sourceUrl(), context.sourceLicense(), context.publisher(),
                        context.trustLevel(), context.version(), context.expiresAt()),
                List.copyOf(ranked.candidate().sources));
    }

    private double round(double score) { return Math.round(score * 1_000_000d) / 1_000_000d; }
    private double trustWeight(String level) {
        return switch (level == null ? "COMMUNITY" : level) {
            case "OFFICIAL" -> 1.00; case "INTERNAL" -> .90; case "PROFESSIONAL" -> .85; default -> .60;
        };
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final class Candidate {
        private final UUID id;
        private double rrfScore;
        private final Set<String> sources = new LinkedHashSet<>();
        private Candidate(UUID id) { this.id = id; }
        private void add(String source, double score) { sources.add(source); rrfScore += score; }
        private UUID id() { return id; }
        private double rrfScore() { return rrfScore; }
    }

    private record RankedContext(Candidate candidate, KnowledgeModels.ChunkContext context, double score) {}
}

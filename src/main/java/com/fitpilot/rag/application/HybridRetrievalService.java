package com.fitpilot.rag.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.domain.KnowledgeModels;
import com.fitpilot.rag.domain.RagCorpus;
import com.fitpilot.rag.domain.RagTuningProfile;
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
    private final EvidenceSupportGate evidenceGate;

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
        this.evidenceGate = new EvidenceSupportGate(analyzer,
                properties.getRetrieval().isEvidenceGateEnabled(),
                properties.getRetrieval().getEvidenceMinQueryTermCoverage());
    }

    @Observed(name = "fitpilot.rag.search")
    public RagDtos.SearchResponse search(String query, int requestedTopK, String category) {
        return search(null, query, requestedTopK, category);
    }

    public RagDtos.SearchResponse search(Long userId, String query, int requestedTopK, String category) {
        return search(userId, query, requestedTopK, category, liveProfile(), RagCorpus.LIVE, true);
    }

    /** Runs a tuning profile against the default corpus without recording a retrieval. */
    public RagDtos.SearchResponse searchOffline(String query, int requestedTopK, String category,
                                                RagTuningProfile profile) {
        return searchOffline(query, requestedTopK, category, profile, RagCorpus.LIVE);
    }

    public RagDtos.SearchResponse searchOffline(String query, int requestedTopK, String category,
                                                RagTuningProfile profile, RagCorpus corpus) {
        return search(null, query, requestedTopK, category, profile == null ? liveProfile() : profile, corpus, false);
    }

    private RagDtos.SearchResponse search(Long userId, String query, int requestedTopK, String category,
                                          RagTuningProfile profile, RagCorpus corpus, boolean persistRetrieval) {
        long started = System.nanoTime();
        int topK = Math.max(1, Math.min(requestedTopK, properties.getRetrieval().getMaxTopK()));
        int candidateLimit = Math.max(topK, properties.getRetrieval().getCandidateLimit());
        String normalizedCategory = category == null || category.isBlank() ? null : category.trim();
        String lexicalQuery = analyzer.lexicalText(query);
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        boolean lexicalSucceeded = collectLexical(lexicalQuery, normalizedCategory, candidateLimit,
                profile, corpus, candidates);
        boolean vectorSucceeded = collectVector(query, normalizedCategory, candidateLimit, profile, corpus, candidates);
        if (!lexicalSucceeded && !vectorSucceeded) {
            metrics.rag("UNAVAILABLE", "FAILED", elapsedMillis(started));
            throw new BusinessException(ErrorCode.RAG_RETRIEVAL_UNAVAILABLE,
                    "knowledge retrieval services are unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }

        List<UUID> ids = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed())
                .map(Candidate::id).toList();
        Map<UUID, KnowledgeModels.ChunkContext> contexts = repository.contexts(ids, corpus);
        Set<String> queryTerms = new LinkedHashSet<>(analyzer.tokens(query));
        Map<UUID, RankedContext> parents = new LinkedHashMap<>();
        for (UUID id : ids) {
            Candidate candidate = candidates.get(id);
            KnowledgeModels.ChunkContext context = contexts.get(id);
            if (context == null) continue;
            double score = rerank(query, queryTerms, candidate.rrfScore(), context, profile);
            RankedContext ranked = new RankedContext(candidate, context, score);
            parents.merge(context.parentChunkId(), ranked, this::better);
        }
        List<RankedContext> rankedParents = parents.values().stream()
                .sorted(Comparator.comparingDouble(RankedContext::score).reversed()).toList();
        List<RagDtos.RetrievedContext> results = rankedParents.stream()
                .filter(ranked -> evidenceGate.verify(query, evidenceText(ranked.context())).supported())
                .limit(topK).map(this::toDto).toList();
        String mode = lexicalSucceeded && vectorSucceeded ? "HYBRID_RRF"
                : lexicalSucceeded ? "BM25_ONLY" : "VECTOR_ONLY";
        UUID retrievalId = persistRetrieval
                ? governance.saveRetrieval(userId, hash(query), query, normalizedCategory, mode, results) : null;
        String evidenceDecision = results.isEmpty() ? "ABSTAINED" : "SUPPORTED";
        String evidenceReason = evidenceReason(query, rankedParents, results);
        metrics.rag(mode, evidenceDecision, elapsedMillis(started));
        return new RagDtos.SearchResponse(retrievalId, query, mode, embeddings.name(), candidates.size(), results,
                evidenceDecision, evidenceReason);
    }

    private String evidenceReason(String query, List<RankedContext> rankedParents,
                                   List<RagDtos.RetrievedContext> results) {
        if (!results.isEmpty()) return "EVIDENCE_FOUND";
        if (rankedParents.isEmpty()) return "NO_CANDIDATE";
        return evidenceGate.verify(query, evidenceText(rankedParents.getFirst().context())).reason();
    }

    private String evidenceText(KnowledgeModels.ChunkContext context) {
        return context.title() + " " + context.heading() + " " + context.childContent() + " " + context.parentContent();
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private boolean collectLexical(String query, String category, int limit, RagTuningProfile profile,
                                   RagCorpus corpus, Map<UUID, Candidate> candidates) {
        if (profile.bm25Weight() == 0) return false;
        try {
            List<KnowledgeModels.SearchHit> hits = lexicalIndex.search(query, category, limit, corpus);
            for (int i = 0; i < hits.size(); i++) {
                Candidate candidate = candidates.computeIfAbsent(hits.get(i).chunkId(), Candidate::new);
                candidate.add("BM25", profile.bm25Weight() * reciprocalRank(i + 1, profile.rrfK()));
            }
            return true;
        } catch (RuntimeException failure) {
            log.warn("BM25 retrieval unavailable, degrading to vector search: {}", failure.getMessage());
            return false;
        }
    }

    private boolean collectVector(String query, String category, int limit, RagTuningProfile profile,
                                  RagCorpus corpus, Map<UUID, Candidate> candidates) {
        if (profile.vectorWeight() == 0) return false;
        try {
            List<KnowledgeModels.SearchHit> hits = repository.vectorSearch(embeddings.embed(query), category, limit, corpus);
            for (int i = 0; i < hits.size(); i++) {
                Candidate candidate = candidates.computeIfAbsent(hits.get(i).chunkId(), Candidate::new);
                candidate.add("VECTOR", profile.vectorWeight() * reciprocalRank(i + 1, profile.rrfK()));
            }
            return true;
        } catch (RuntimeException failure) {
            log.warn("Vector retrieval unavailable, degrading to BM25: {}", failure.getMessage());
            return false;
        }
    }

    private double reciprocalRank(int rank, int rrfK) {
        return 1.0 / (rrfK + rank);
    }

    private double rerank(String query, Set<String> queryTerms, double rrf,
                          KnowledgeModels.ChunkContext context, RagTuningProfile profile) {
        if ("NONE".equals(profile.rerankStrategy())) return rrf;
        Set<String> candidateTerms = new LinkedHashSet<>(analyzer.tokens(
                context.title() + " " + context.heading() + " " + context.childContent()));
        long matches = queryTerms.stream().filter(candidateTerms::contains).count();
        double overlap = queryTerms.isEmpty() ? 0 : (double) matches / queryTerms.size();
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        String candidateText = (context.title() + " " + context.heading() + " " + context.childContent())
                .toLowerCase(Locale.ROOT);
        double phraseBoost = normalizedQuery.length() >= 2 && candidateText.contains(normalizedQuery) ? 0.15 : 0;
        double trustBoost = "TERM_OVERLAP_TRUST".equals(profile.rerankStrategy())
                ? trustWeight(context.trustLevel()) * 0.04 : 0;
        return rrf * 10 + overlap * 0.35 + phraseBoost + trustBoost;
    }

    private RagTuningProfile liveProfile() {
        RagProperties.Chunking chunking = properties.getChunking();
        RagProperties.Retrieval retrieval = properties.getRetrieval();
        return new RagTuningProfile("live", chunking.getParentMaxChars(), chunking.getChildMaxChars(),
                chunking.getChildOverlapChars(), retrieval.getRrfK(), 1, 1, "TERM_OVERLAP_TRUST");
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

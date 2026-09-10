package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.domain.EvaluationCases;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.application.RagFeedbackService;
import com.fitpilot.rag.domain.RagTuningProfile;
import com.fitpilot.rag.dto.RagDtos;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RagEvaluationRunner {
    private final EvaluationDatasetLoader datasets;
    private final EvaluationRepository repository;
    private final ObjectProvider<HybridRetrievalService> retrieval;
    private final ObjectProvider<KnowledgeIngestionService> ingestion;
    private final EvaluationMetricCalculator metrics;
    private final EvaluationReportService reports;
    private final RagFeedbackService feedback;

    public RagEvaluationRunner(EvaluationDatasetLoader datasets, EvaluationRepository repository,
                               ObjectProvider<HybridRetrievalService> retrieval,
                               ObjectProvider<KnowledgeIngestionService> ingestion,
                               EvaluationMetricCalculator metrics, EvaluationReportService reports,
                               RagFeedbackService feedback) {
        this.datasets = datasets;
        this.repository = repository;
        this.retrieval = retrieval;
        this.ingestion = ingestion;
        this.metrics = metrics;
        this.reports = reports;
        this.feedback = feedback;
    }

    public void run(UUID runId, List<EvaluationCases.RagCase> cases,
                    EvaluationCases.RagExperiment experiment,
                    String workerId, Runnable ensureLease) throws Exception {
        if (!experiment.profiles().isEmpty()) {
            runFeedbackExperiment(runId, cases, experiment, workerId, ensureLease);
            return;
        }
        KnowledgeIngestionService loader = ingestion.getIfAvailable();
        List<UUID> evaluationDocuments = new ArrayList<>();
        boolean cleaned = false;
        try {
            HybridRetrievalService service = retrieval.getIfAvailable();
            if (service == null || loader == null) throw new IllegalStateException("RAG is disabled");
            for (var document : datasets.ragCorpus()) {
                evaluationDocuments.add(loader.ingest(scoped(document, runId)).id());
            }
            EvaluationMetricCalculator.RagAccumulator accumulator =
                    new EvaluationMetricCalculator.RagAccumulator();
            for (var item : cases) {
                ensureLease.run();
                long started = System.nanoTime();
                RagDtos.SearchResponse response = service.search(item.query(), 5, item.category());
                EvaluationMetricCalculator.RagCaseMetrics caseMetrics =
                        metrics.scoreRagCase(item.expectedSourceUrls(), response.contexts());
                accumulator.add(item.category(), caseMetrics);
                repository.ragResult(runId, item.id(), metrics.hash(item.query()), item.expectedSourceUrls(),
                        caseMetrics.actualSources(), caseMetrics.recall(), caseMetrics.reciprocalRank(),
                        caseMetrics.ndcg(), caseMetrics.precision(), caseMetrics.contextRecall(),
                        caseMetrics.citationValid(), metrics.elapsed(started));
            }
            cleanupDocuments(loader, evaluationDocuments);
            cleaned = true;
            reports.finishRag(runId, workerId, cases.size(), accumulator);
        } finally {
            if (!cleaned && loader != null) cleanupDocuments(loader, evaluationDocuments);
        }
    }

    private void runFeedbackExperiment(UUID runId, List<EvaluationCases.RagCase> cases,
                                       EvaluationCases.RagExperiment experiment,
                                       String workerId, Runnable ensureLease) {
        KnowledgeIngestionService loader = ingestion.getIfAvailable();
        HybridRetrievalService service = retrieval.getIfAvailable();
        if (service == null || loader == null) throw new IllegalStateException("RAG is disabled");

        Map<String, EvaluationMetricCalculator.RagAccumulator> profileMetrics = new LinkedHashMap<>();
        for (RagTuningProfile profile : experiment.profiles()) {
            ensureLease.run();
            List<UUID> documents = new ArrayList<>();
            try {
                for (EvaluationCases.RagDocumentRef reference : experiment.documents()) {
                    RagDtos.IngestDocumentRequest source = feedback.evaluationDocument(
                            new RagFeedbackService.EvaluationDocumentRef(reference.documentId(),
                                    reference.version(), reference.sourceUrl(), reference.category()));
                    documents.add(loader.ingest(scoped(source, runId, profile), profile).id());
                }
                EvaluationMetricCalculator.RagAccumulator accumulator =
                        new EvaluationMetricCalculator.RagAccumulator();
                for (EvaluationCases.RagCase item : cases) {
                    ensureLease.run();
                    long started = System.nanoTime();
                    RagDtos.SearchResponse response = service.searchOffline(item.query(), 5,
                            scopedCategory(item.category(), runId, profile), profile);
                    EvaluationMetricCalculator.RagCaseMetrics caseMetrics =
                            metrics.scoreRagCase(item.expectedSourceUrls(), response.contexts());
                    accumulator.add(item.category(), caseMetrics);
                    repository.ragResult(runId, profile.id(), item.id(), metrics.hash(item.query()),
                            item.expectedSourceUrls(), caseMetrics.actualSources(), caseMetrics.recall(),
                            caseMetrics.reciprocalRank(), caseMetrics.ndcg(), caseMetrics.precision(),
                            caseMetrics.contextRecall(), caseMetrics.citationValid(), metrics.elapsed(started));
                }
                profileMetrics.put(profile.id(), accumulator);
            } finally {
                cleanupDocuments(loader, documents);
            }
        }
        reports.finishRagExperiment(runId, workerId, cases.size(), experiment.profiles(), profileMetrics);
    }

    private RagDtos.IngestDocumentRequest scoped(RagDtos.IngestDocumentRequest source, UUID runId) {
        Map<String, String> metadata = new LinkedHashMap<>(
                source.metadata() == null ? Map.of() : source.metadata());
        metadata.put("evaluationRunId", runId.toString());
        return new RagDtos.IngestDocumentRequest(scopedExternalId(source.externalId(), runId, "baseline"),
                source.title(),
                source.category(), source.sourceUrl(), source.sourceLicense(), source.format(),
                source.content(), metadata, source.publisher(), source.trustLevel(),
                source.effectiveFrom(), source.expiresAt());
    }

    private RagDtos.IngestDocumentRequest scoped(RagDtos.IngestDocumentRequest source, UUID runId,
                                                  RagTuningProfile profile) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("evaluationRunId", runId.toString());
        metadata.put("evaluationProfile", profile.id());
        return new RagDtos.IngestDocumentRequest(scopedExternalId(source.externalId(), runId, profile.id()),
                source.title(), scopedCategory(source.category(), runId, profile), source.sourceUrl(),
                source.sourceLicense(), source.format(), source.content(), metadata, source.publisher(),
                source.trustLevel(), source.effectiveFrom(), source.expiresAt());
    }

    private String scopedCategory(String category, UUID runId, RagTuningProfile profile) {
        return "ev-" + runId.toString().substring(0, 16) + "-" + profile.id() + "-"
                + metrics.hash(category == null ? "uncategorized" : category).substring(0, 8);
    }

    private String scopedExternalId(String externalId, UUID runId, String profileId) {
        String suffix = "-ev-" + runId + "-" + profileId;
        int prefixLength = Math.min(externalId.length(), 160 - suffix.length());
        return externalId.substring(0, prefixLength) + suffix;
    }

    private void cleanupDocuments(KnowledgeIngestionService loader, List<UUID> documentIds) {
        documentIds.forEach(id -> {
            try {
                loader.delete(id);
            } catch (RuntimeException ignored) {
                // Evaluation cleanup is best-effort and indexing has its own deletion retry queue.
            }
        });
    }
}

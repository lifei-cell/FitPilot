package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.domain.EvaluationCases;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.application.KnowledgeIngestionService;
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

    public RagEvaluationRunner(EvaluationDatasetLoader datasets, EvaluationRepository repository,
                               ObjectProvider<HybridRetrievalService> retrieval,
                               ObjectProvider<KnowledgeIngestionService> ingestion,
                               EvaluationMetricCalculator metrics, EvaluationReportService reports) {
        this.datasets = datasets;
        this.repository = repository;
        this.retrieval = retrieval;
        this.ingestion = ingestion;
        this.metrics = metrics;
        this.reports = reports;
    }

    public void run(UUID runId, List<EvaluationCases.RagCase> cases,
                    String workerId, Runnable ensureLease) throws Exception {
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

    private RagDtos.IngestDocumentRequest scoped(RagDtos.IngestDocumentRequest source, UUID runId) {
        Map<String, String> metadata = new LinkedHashMap<>(
                source.metadata() == null ? Map.of() : source.metadata());
        metadata.put("evaluationRunId", runId.toString());
        return new RagDtos.IngestDocumentRequest(source.externalId() + "-" + runId, source.title(),
                source.category(), source.sourceUrl(), source.sourceLicense(), source.format(),
                source.content(), metadata, source.publisher(), source.trustLevel(),
                source.effectiveFrom(), source.expiresAt());
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

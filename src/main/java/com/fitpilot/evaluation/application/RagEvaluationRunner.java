package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.domain.EvaluationCases;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.application.RagFeedbackService;
import com.fitpilot.rag.domain.RagCorpus;
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
        RagCorpus corpus = RagCorpus.evaluation();
        RagCorpus feedbackCorpus = RagCorpus.evaluation();
        List<UUID> evaluationDocuments = new ArrayList<>();
        List<UUID> feedbackDocuments = new ArrayList<>();
        boolean cleaned = false;
        try {
            HybridRetrievalService service = retrieval.getIfAvailable();
            if (service == null || loader == null) throw new IllegalStateException("RAG is disabled");
            for (var document : datasets.ragCorpus()) {
                ensureLease.run();
                evaluationDocuments.add(loader.ingest(document, null, corpus).id());
            }
            ingestRevisions(loader, experiment.documents(), null, feedbackCorpus, feedbackDocuments, ensureLease);
            EvaluationMetricCalculator.RagAccumulator accumulator =
                    new EvaluationMetricCalculator.RagAccumulator();
            for (var item : cases) {
                ensureLease.run();
                long started = System.nanoTime();
                RagCorpus caseCorpus = item.id().startsWith("dynamic-") ? feedbackCorpus : corpus;
                RagDtos.SearchResponse response = service.searchOffline(item.query(), 5, item.category(), null, caseCorpus);
                EvaluationMetricCalculator.RagCaseMetrics caseMetrics =
                        metrics.scoreRagCase(item.expectedSourceUrls(), response.contexts());
                accumulator.add(item.category(), caseMetrics);
                repository.ragResult(runId, item.id(), metrics.hash(item.query()), item.expectedSourceUrls(),
                        caseMetrics.actualSources(), caseMetrics.recall(), caseMetrics.reciprocalRank(),
                        caseMetrics.ndcg(), caseMetrics.precision(), caseMetrics.contextRecall(),
                        caseMetrics.citationValid(), metrics.elapsed(started));
            }
            cleanupDocuments(loader, evaluationDocuments, corpus);
            cleanupDocuments(loader, feedbackDocuments, feedbackCorpus);
            cleaned = true;
            reports.finishRag(runId, workerId, cases.size(), accumulator);
        } finally {
            if (!cleaned && loader != null) {
                cleanupDocuments(loader, evaluationDocuments, corpus);
                cleanupDocuments(loader, feedbackDocuments, feedbackCorpus);
            }
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
            RagCorpus corpus = RagCorpus.evaluation();
            List<UUID> documents = new ArrayList<>();
            try {
                ingestRevisions(loader, experiment.documents(), profile, corpus, documents, ensureLease);
                EvaluationMetricCalculator.RagAccumulator accumulator =
                        new EvaluationMetricCalculator.RagAccumulator();
                for (EvaluationCases.RagCase item : cases) {
                    ensureLease.run();
                    long started = System.nanoTime();
                    RagDtos.SearchResponse response = service.searchOffline(item.query(), 5,
                            item.category(), profile, corpus);
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
                cleanupDocuments(loader, documents, corpus);
            }
        }
        reports.finishRagExperiment(runId, workerId, cases.size(), experiment.profiles(), profileMetrics);
    }

    private void ingestRevisions(KnowledgeIngestionService loader, List<EvaluationCases.RagDocumentRef> references,
                                 RagTuningProfile profile, RagCorpus corpus, List<UUID> documents, Runnable ensureLease) {
        for (var reference : references) {
            ensureLease.run();
            RagDtos.IngestDocumentRequest source = feedback.evaluationDocument(new RagFeedbackService.EvaluationDocumentRef(
                    reference.documentId(), reference.version(), reference.sourceUrl(), reference.category()));
            documents.add(loader.ingest(source, profile, corpus).id());
        }
    }

    private void cleanupDocuments(KnowledgeIngestionService loader, List<UUID> documentIds, RagCorpus corpus) {
        documentIds.forEach(id -> {
            try {
                loader.delete(id, corpus);
            } catch (RuntimeException ignored) {
                // Evaluation cleanup is best-effort and indexing has its own deletion retry queue.
            }
        });
    }
}

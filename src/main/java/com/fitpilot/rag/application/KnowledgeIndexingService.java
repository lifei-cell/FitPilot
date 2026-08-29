package com.fitpilot.rag.application;

import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.infrastructure.ElasticsearchKnowledgeIndex;
import com.fitpilot.rag.infrastructure.KnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "fitpilot.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexingService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexingService.class);
    private final KnowledgeRepository repository;
    private final ElasticsearchKnowledgeIndex index;
    private final RagProperties properties;

    public KnowledgeIndexingService(KnowledgeRepository repository, ElasticsearchKnowledgeIndex index,
                                    RagProperties properties) {
        this.repository = repository;
        this.index = index;
        this.properties = properties;
    }

    public boolean indexNow(UUID documentId) {
        if (!repository.markIndexing(documentId)) return false;
        try {
            index.replaceDocument(documentId, repository.chunksForIndex(documentId));
            repository.markIndexed(documentId);
            return true;
        } catch (RuntimeException failure) {
            repository.markFailed(documentId, failure);
            log.warn("Knowledge document indexing failed documentId={}: {}", documentId, failure.getMessage());
            return false;
        }
    }

    public boolean reindex(UUID documentId) {
        repository.resetPending(documentId);
        return indexNow(documentId);
    }

    public void deleteFromIndex(UUID documentId) {
        try {
            index.deleteDocument(documentId);
        } catch (RuntimeException failure) {
            log.warn("Stale Elasticsearch chunks will be ignored after PostgreSQL delete documentId={}: {}",
                    documentId, failure.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${fitpilot.rag.indexing.fixed-delay-ms:5000}")
    void retryPending() {
        for (UUID documentId : repository.retryCandidates(properties.getIndexing().getRetryBatchSize())) {
            indexNow(documentId);
        }
    }
}

package com.fitpilot.rag.application;

import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.domain.KnowledgeModels.DocumentVersion;
import com.fitpilot.rag.domain.KnowledgeModels.IndexClaim;
import com.fitpilot.rag.infrastructure.ElasticsearchKnowledgeIndex;
import com.fitpilot.rag.infrastructure.KnowledgeRepository;
import com.fitpilot.rag.infrastructure.RagGovernanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "fitpilot.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexingService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexingService.class);
    private final KnowledgeRepository repository;
    private final ElasticsearchKnowledgeIndex index;
    private final RagProperties properties;
    private final RagGovernanceRepository governance;

    public KnowledgeIndexingService(KnowledgeRepository repository, ElasticsearchKnowledgeIndex index,
                                    RagProperties properties, RagGovernanceRepository governance) {
        this.repository = repository;
        this.index = index;
        this.properties = properties;
        this.governance = governance;
    }

    public boolean indexNow(DocumentVersion document) {
        return index(document, false);
    }

    public boolean reindex(DocumentVersion document) {
        return index(document, true);
    }

    private boolean index(DocumentVersion document, boolean reindex) {
        IndexClaim claim = repository.claimIndexing(document, reindex).orElse(null);
        if (claim == null) return false;
        try {
            index.publish(claim);
            return repository.markIndexed(claim);
        } catch (RuntimeException failure) {
            if (repository.markFailed(claim, failure)) {
                log.warn("Knowledge document indexing failed document={}: {}", document, failure.getMessage());
            }
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${fitpilot.rag.indexing.fixed-delay-ms:5000}")
    void retryPending() {
        for (DocumentVersion document : repository.retryCandidates(properties.getIndexing().getRetryBatchSize())) {
            indexNow(document);
        }
        for (RagGovernanceRepository.DeletionTask task : governance.claimDeletionTasks(
                properties.getIndexing().getRetryBatchSize())) {
            try {
                index.deleteDocument(task.document(), task.slotCount());
                governance.deletionSucceeded(task);
            } catch (RuntimeException failure) {
                if (governance.deletionFailed(task, failure)) {
                    log.warn("Knowledge deletion propagation failed document={}: {}", task.document(), failure.getMessage());
                }
            }
        }
    }
}

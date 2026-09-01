package com.fitpilot.rag.application;

import com.fitpilot.observability.FitPilotMetrics;
import com.fitpilot.rag.config.RagProperties;
import com.fitpilot.rag.domain.KnowledgeModels;
import com.fitpilot.rag.embedding.EmbeddingProvider;
import com.fitpilot.rag.infrastructure.ElasticsearchKnowledgeIndex;
import com.fitpilot.rag.infrastructure.KnowledgeRepository;
import com.fitpilot.rag.infrastructure.RagGovernanceRepository;
import com.fitpilot.rag.processing.TextAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HybridRetrievalServiceTest {
    @Test
    void usesTrustOnlyAsBoundedRerankSignalAndPersistsRetrieval() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        ElasticsearchKnowledgeIndex elasticsearch = mock(ElasticsearchKnowledgeIndex.class);
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        TextAnalyzer analyzer = mock(TextAnalyzer.class);
        FitPilotMetrics metrics = mock(FitPilotMetrics.class);
        RagGovernanceRepository governance = mock(RagGovernanceRepository.class);
        RagProperties properties = new RagProperties();
        UUID officialChunk = UUID.randomUUID(), communityChunk = UUID.randomUUID();
        UUID retrievalId = UUID.randomUUID();
        when(analyzer.lexicalText("rpe")).thenReturn("rpe");
        when(analyzer.tokens("rpe")).thenReturn(List.of("rpe"));
        when(embeddings.embed("rpe")).thenReturn(new float[]{1});
        when(embeddings.name()).thenReturn("test");
        when(elasticsearch.search("rpe", null, properties.getRetrieval().getCandidateLimit())).thenReturn(List.of(
                new KnowledgeModels.SearchHit(officialChunk, 1), new KnowledgeModels.SearchHit(communityChunk, .9)));
        when(repository.vectorSearch(any(), isNull(), eq(properties.getRetrieval().getCandidateLimit()))).thenReturn(List.of(
                new KnowledgeModels.SearchHit(communityChunk, 1), new KnowledgeModels.SearchHit(officialChunk, .9)));
        LinkedHashMap<UUID, KnowledgeModels.ChunkContext> contexts = new LinkedHashMap<>();
        contexts.put(officialChunk, context(officialChunk, "OFFICIAL"));
        contexts.put(communityChunk, context(communityChunk, "COMMUNITY"));
        when(repository.contexts(anyList())).thenReturn(contexts);
        when(governance.saveRetrieval(eq(7L), anyString(), eq("rpe"), isNull(), eq("HYBRID_RRF"), anyList()))
                .thenReturn(retrievalId);
        HybridRetrievalService service = new HybridRetrievalService(repository, elasticsearch, embeddings, analyzer,
                properties, metrics, governance);

        var response = service.search(7L, "rpe", 2, null);

        assertThat(response.retrievalId()).isEqualTo(retrievalId);
        assertThat(response.contexts()).extracting(item -> item.citation().trustLevel())
                .containsExactly("OFFICIAL", "COMMUNITY");
        assertThat(response.contexts().getFirst().score() - response.contexts().getLast().score()).isLessThan(.02);
    }

    private KnowledgeModels.ChunkContext context(UUID chunkId, String trust) {
        return new KnowledgeModels.ChunkContext(chunkId, UUID.randomUUID(), UUID.randomUUID(), "RPE", "training",
                "RPE", "rpe", "rpe 指南", "https://example.org/" + trust, "CC-BY", "publisher", trust, 1, null);
    }
}

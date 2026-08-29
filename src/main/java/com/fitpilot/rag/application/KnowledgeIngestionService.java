package com.fitpilot.rag.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.rag.domain.KnowledgeModels;
import com.fitpilot.rag.dto.RagDtos;
import com.fitpilot.rag.embedding.EmbeddingProvider;
import com.fitpilot.rag.infrastructure.KnowledgeRepository;
import com.fitpilot.rag.processing.DocumentParser;
import com.fitpilot.rag.processing.ParentChildChunker;
import com.fitpilot.rag.processing.TextAnalyzer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "fitpilot.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIngestionService {
    private final KnowledgeRepository repository;
    private final KnowledgeIndexingService indexing;
    private final DocumentParser parser;
    private final ParentChildChunker chunker;
    private final TextAnalyzer analyzer;
    private final EmbeddingProvider embeddings;

    public KnowledgeIngestionService(KnowledgeRepository repository, KnowledgeIndexingService indexing,
                                     DocumentParser parser, ParentChildChunker chunker, TextAnalyzer analyzer,
                                     EmbeddingProvider embeddings) {
        this.repository = repository;
        this.indexing = indexing;
        this.parser = parser;
        this.chunker = chunker;
        this.analyzer = analyzer;
        this.embeddings = embeddings;
    }

    public RagDtos.DocumentView ingest(RagDtos.IngestDocumentRequest request) {
        validateMetadata(request.metadata());
        String format = request.format().toUpperCase(Locale.ROOT);
        List<ParentChildChunker.ParentChunk> plan = chunker.chunk(
                parser.parse(format, request.title(), request.content()));
        if (plan.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "document contains no indexable text",
                    HttpStatus.BAD_REQUEST);
        }

        UUID proposedId = UUID.randomUUID();
        List<KnowledgeModels.StoredChunk> chunks = new ArrayList<>();
        for (ParentChildChunker.ParentChunk parent : plan) {
            UUID parentId = UUID.randomUUID();
            chunks.add(new KnowledgeModels.StoredChunk(parentId, null, "PARENT", parent.ordinal(),
                    parent.heading(), parent.content(), analyzer.lexicalText(parent.content()), null));
            for (ParentChildChunker.ChildChunk child : parent.children()) {
                String searchable = request.title() + " " + parent.heading() + " " + child.content();
                chunks.add(new KnowledgeModels.StoredChunk(UUID.randomUUID(), parentId, "CHILD",
                        child.ordinal(), parent.heading(), child.content(), analyzer.lexicalText(searchable),
                        embeddings.embed(searchable)));
            }
        }
        KnowledgeRepository.DocumentWrite write = new KnowledgeRepository.DocumentWrite(
                proposedId, request.externalId(), request.title().trim(), request.category().trim(),
                request.sourceUrl().trim(), request.sourceLicense().trim(), format, sha256(request.content()),
                request.content(), request.metadata() == null ? Map.of() : Map.copyOf(request.metadata()));
        UUID documentId = repository.replace(write, chunks);
        indexing.indexNow(documentId);
        return view(documentId);
    }

    public RagDtos.DocumentView reindex(UUID id) {
        require(id);
        if (!indexing.reindex(id)) {
            KnowledgeModels.Document document = require(id);
            throw new BusinessException(ErrorCode.KNOWLEDGE_INDEX_FAILED,
                    document.indexError() == null ? "knowledge indexing failed" : document.indexError(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        return view(id);
    }

    public List<RagDtos.DocumentView> list(int limit) {
        return repository.listDocuments(Math.max(1, Math.min(limit, 100))).stream()
                .map(document -> toView(document, repository.countChunks(document.id(), "PARENT"),
                        repository.countChunks(document.id(), "CHILD"))).toList();
    }

    public void delete(UUID id) {
        require(id);
        if (!repository.delete(id)) throw notFound();
        indexing.deleteFromIndex(id);
    }

    private RagDtos.DocumentView view(UUID id) {
        KnowledgeModels.Document document = require(id);
        return toView(document, repository.countChunks(id, "PARENT"), repository.countChunks(id, "CHILD"));
    }

    private RagDtos.DocumentView toView(KnowledgeModels.Document document, int parents, int children) {
        return new RagDtos.DocumentView(document.id(), document.externalId(), document.title(), document.category(),
                document.sourceUrl(), document.sourceLicense(), document.format(), document.indexStatus(),
                document.indexError(), document.version(), parents, children, document.updatedAt(), document.indexedAt());
    }

    private KnowledgeModels.Document require(UUID id) {
        return repository.findDocument(id).orElseThrow(this::notFound);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_NOT_FOUND,
                "knowledge document not found", HttpStatus.NOT_FOUND);
    }

    private void validateMetadata(Map<String, String> metadata) {
        if (metadata == null) return;
        if (metadata.size() > 30 || metadata.entrySet().stream().anyMatch(entry -> entry.getKey().length() > 80
                || entry.getValue() == null || entry.getValue().length() > 500)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "document metadata is too large",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}

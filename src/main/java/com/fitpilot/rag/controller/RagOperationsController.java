package com.fitpilot.rag.controller;

import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.dto.RagDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/operations/rag/documents")
@ConditionalOnProperty(prefix = "fitpilot.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagOperationsController {
    private final KnowledgeIngestionService service;

    public RagOperationsController(KnowledgeIngestionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RagDtos.DocumentView> ingest(@Valid @RequestBody RagDtos.IngestDocumentRequest request) {
        return ApiResponse.success(service.ingest(request));
    }

    @GetMapping
    ApiResponse<List<RagDtos.DocumentView>> list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.list(limit));
    }

    @PostMapping("/{id}/reindex")
    ApiResponse<RagDtos.DocumentView> reindex(@PathVariable UUID id) {
        return ApiResponse.success(service.reindex(id));
    }

    @GetMapping("/{id}/revisions")
    ApiResponse<List<RagDtos.RevisionView>> revisions(@PathVariable UUID id) {
        return ApiResponse.success(service.revisions(id));
    }

    @PostMapping("/{id}/revisions/{version}/restore")
    ApiResponse<RagDtos.DocumentView> restore(@PathVariable UUID id, @PathVariable @Min(1) int version) {
        return ApiResponse.success(service.restore(id, version));
    }

    @GetMapping("/{id}/delete-status")
    ApiResponse<RagDtos.DeleteStatus> deleteStatus(@PathVariable UUID id) {
        return ApiResponse.success(service.deleteStatus(id));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.success();
    }
}

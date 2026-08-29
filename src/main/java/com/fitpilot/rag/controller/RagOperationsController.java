package com.fitpilot.rag.controller;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.security.SecureTokenMatcher;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.config.RagProperties;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final RagProperties properties;

    public RagOperationsController(KnowledgeIngestionService service, RagProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RagDtos.DocumentView> ingest(@RequestHeader("X-Operations-Token") String token,
                                             @Valid @RequestBody RagDtos.IngestDocumentRequest request) {
        authorize(token);
        return ApiResponse.success(service.ingest(request));
    }

    @GetMapping
    ApiResponse<List<RagDtos.DocumentView>> list(@RequestHeader("X-Operations-Token") String token,
                                                 @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        authorize(token);
        return ApiResponse.success(service.list(limit));
    }

    @PostMapping("/{id}/reindex")
    ApiResponse<RagDtos.DocumentView> reindex(@RequestHeader("X-Operations-Token") String token,
                                              @PathVariable UUID id) {
        authorize(token);
        return ApiResponse.success(service.reindex(id));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@RequestHeader("X-Operations-Token") String token, @PathVariable UUID id) {
        authorize(token);
        service.delete(id);
        return ApiResponse.success();
    }

    private void authorize(String candidate) {
        String expected = properties.getOperationsToken();
        boolean valid = SecureTokenMatcher.matches(expected, candidate);
        if (!valid) throw new BusinessException(ErrorCode.ACCESS_DENIED,
                "invalid operations token", HttpStatus.FORBIDDEN);
    }
}

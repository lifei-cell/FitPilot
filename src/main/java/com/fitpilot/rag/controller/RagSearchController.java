package com.fitpilot.rag.controller;

import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.dto.RagDtos;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import com.fitpilot.common.security.CurrentUser;

@Validated
@RestController
@RequestMapping("/api/v1/rag")
@ConditionalOnProperty(prefix = "fitpilot.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagSearchController {
    private final HybridRetrievalService service;

    public RagSearchController(HybridRetrievalService service) { this.service = service; }

    @GetMapping("/search")
    ApiResponse<RagDtos.SearchResponse> search(
            @RequestParam("q") @NotBlank @Size(max = 1000) String query,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int topK,
            @RequestParam(required = false) @Size(max = 80) String category,
            Authentication auth) {
        return ApiResponse.success(service.search(CurrentUser.id(auth), query, topK, category));
    }
}

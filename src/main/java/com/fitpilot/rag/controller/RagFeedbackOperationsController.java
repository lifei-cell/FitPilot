package com.fitpilot.rag.controller;

import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.rag.application.RagFeedbackService;
import com.fitpilot.rag.dto.RagDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/operations/rag/feedback")
@ConditionalOnProperty(prefix = "fitpilot.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagFeedbackOperationsController {
    private final RagFeedbackService service;
    public RagFeedbackOperationsController(RagFeedbackService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<RagDtos.FeedbackView>> pending(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.pending(limit));
    }
    @PutMapping("/{id}/review")
    ApiResponse<Void> review(@PathVariable UUID id, @Valid @RequestBody RagDtos.FeedbackReviewRequest request) {
        service.review(id, request); return ApiResponse.success();
    }
    @GetMapping("/summary")
    ApiResponse<RagDtos.FeedbackSummary> summary() {
        return ApiResponse.success(service.summary());
    }
}

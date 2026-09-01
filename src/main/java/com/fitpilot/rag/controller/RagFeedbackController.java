package com.fitpilot.rag.controller;

import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.security.CurrentUser;
import com.fitpilot.rag.application.RagFeedbackService;
import com.fitpilot.rag.dto.RagDtos;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rag/retrievals")
@ConditionalOnProperty(prefix = "fitpilot.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagFeedbackController {
    private final RagFeedbackService service;
    public RagFeedbackController(RagFeedbackService service) { this.service = service; }

    @PutMapping("/{retrievalId}/feedback")
    ApiResponse<RagDtos.FeedbackView> feedback(@PathVariable UUID retrievalId,
                                               @Valid @RequestBody RagDtos.FeedbackRequest request,
                                               Authentication auth) {
        return ApiResponse.success(service.submit(CurrentUser.id(auth), retrievalId, request));
    }
}

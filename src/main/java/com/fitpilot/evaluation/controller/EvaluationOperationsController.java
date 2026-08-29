package com.fitpilot.evaluation.controller;

import com.fitpilot.agent.config.AgentProperties;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.operations.OperationsAuthorizer;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.evaluation.application.EvaluationService;
import com.fitpilot.evaluation.dto.EvaluationDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/evaluations")
public class EvaluationOperationsController {
    private final EvaluationService service;private final OperationsAuthorizer authorizer;private final AgentProperties legacy;
    public EvaluationOperationsController(EvaluationService service,OperationsAuthorizer authorizer,AgentProperties legacy){this.service=service;this.authorizer=authorizer;this.legacy=legacy;}
    @PostMapping("/agent/runs") @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<Map<String,UUID>> agent(@RequestHeader("X-Operations-Token")String token,@Valid @RequestBody(required=false)EvaluationDtos.AgentRunRequest request){authorize(token);return ApiResponse.success(Map.of("runId",service.startAgent(request==null?null:request.mode())));}
    @PostMapping("/rag/runs") @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<Map<String,UUID>> rag(@RequestHeader("X-Operations-Token")String token){authorize(token);return ApiResponse.success(Map.of("runId",service.startRag()));}
    @GetMapping("/runs/{id}") ApiResponse<EvaluationDtos.RunView> get(@RequestHeader("X-Operations-Token")String token,@PathVariable UUID id){authorize(token);return ApiResponse.success(service.find(id).orElseThrow(()->new BusinessException(ErrorCode.EVALUATION_RUN_NOT_FOUND,"evaluation run not found",HttpStatus.NOT_FOUND)));}
    private void authorize(String token){authorizer.authorize(token,legacy.getOperationsToken());}
}

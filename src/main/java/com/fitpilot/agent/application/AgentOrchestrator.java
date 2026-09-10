package com.fitpilot.agent.application;

import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.agent.memory.AgentSessionStore;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.llm.application.LlmGateway;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.observability.FitPilotMetrics;
import com.fitpilot.rag.dto.RagDtos;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AgentOrchestrator {
    private final IntentRouter intentRouter;
    private final ToolExecutionService toolExecution;
    private final PlanProposalService proposals;
    private final PendingActionService pendingActions;
    private final AgentRepository repository;
    private final AgentSessionStore sessions;
    private final LlmGateway llm;
    private final FitPilotMetrics metrics;
    private final ObservationRegistry observations;

    public AgentOrchestrator(IntentRouter intentRouter, ToolExecutionService toolExecution,
                             PlanProposalService proposals, PendingActionService pendingActions,
                             AgentRepository repository, AgentSessionStore sessions,
                             LlmGateway llm, FitPilotMetrics metrics, ObservationRegistry observations) {
        this.intentRouter = intentRouter;
        this.toolExecution = toolExecution;
        this.proposals = proposals;
        this.pendingActions = pendingActions;
        this.repository = repository;
        this.sessions = sessions;
        this.llm = llm;
        this.metrics = metrics;
        this.observations = observations;
    }

    @Observed(name = "fitpilot.agent.workflow")
    public AgentDtos.MessageView message(long userId, UUID sessionId, AgentDtos.MessageRequest request) {
        requireOwnedSession(userId, sessionId);
        long started = System.nanoTime();
        observeUser(userId);
        IntentRouter.RoutingContext routing = intentRouter.begin(userId, sessionId, request.message());
        UUID executionId = routing.executionId();
        sessions.append(sessionId, "user", request.message(), "COMPLETED", executionId, Map.of());
        try {
            LlmModels.Result<LlmModels.WorkflowDecision> decisionResult =
                    intentRouter.decide(routing, request.message());
            LlmModels.WorkflowDecision decision = decisionResult.value();
            Map<String, Object> results = toolExecution.executeReadTools(
                    executionId, userId, request.message(), decision.tools());
            return completeWorkflow(userId, sessionId, request, executionId,
                    started, decision, decisionResult, results);
        } catch (RuntimeException failure) {
            fail(executionId, sessionId, started);
            throw failure;
        }
    }

    private AgentDtos.MessageView completeWorkflow(long userId, UUID sessionId,
                                                    AgentDtos.MessageRequest request, UUID executionId,
                                                    long started, LlmModels.WorkflowDecision decision,
                                                    LlmModels.Result<LlmModels.WorkflowDecision> decisionResult,
                                                    Map<String, Object> results) {
        PlanProposalService.Outcome proposal = proposals.prepare(
                userId, request, executionId, decision, results,
                decisionResult.model(), decisionResult.degraded(), decisionResult.promptVersion(),
                !citations(results).isEmpty());
        if (proposal.state() == PlanProposalService.State.REJECTED) {
            return finishProposalRejection(sessionId, executionId, started, decision, results, proposal);
        }
        if (proposal.state() == PlanProposalService.State.COMPLETED) {
            return finishProposalWithoutAction(sessionId, executionId, started, decision, results, proposal);
        }

        AgentDtos.PendingActionView pending = proposal.pending();
        String fallbackAnswer = compose(decision.intent(), results, pending != null);
        LlmModels.Result<String> answerResult = pending == null
                ? llm.answer(executionId, request.message(), results, fallbackAnswer)
                : new LlmModels.Result<>(fallbackAnswer, proposal.model(), proposal.degraded(),
                proposal.promptVersion(), 0, 0, BigDecimal.ZERO);
        intentRouter.recordUsage(executionId, answerResult);
        boolean degraded = proposal.degraded() || answerResult.degraded();
        appendAssistant(sessionId, executionId, answerResult.value(), results);
        String status = pending == null ? "SUCCEEDED" : "AWAITING_CONFIRMATION";
        repository.finishExecution(executionId, status, elapsed(started), proposal.violations());
        metrics.agent(status, degraded, elapsed(started), proposal.violations());
        return view(executionId, decision, answerResult.value(), pending,
                answerResult.model(), degraded, answerResult.promptVersion(), results);
    }

    private AgentDtos.MessageView finishProposalRejection(
            UUID sessionId, UUID executionId, long started, LlmModels.WorkflowDecision decision,
            Map<String, Object> results, PlanProposalService.Outcome proposal) {
        repository.finishExecution(executionId, "REJECTED", elapsed(started), proposal.violations());
        metrics.agent("REJECTED", proposal.degraded(), elapsed(started), proposal.violations());
        sessions.append(sessionId, "assistant", proposal.answer(), "COMPLETED", executionId, Map.of());
        return view(executionId, decision, proposal.answer(), null,
                proposal.model(), proposal.degraded(), proposal.promptVersion(), results);
    }

    private AgentDtos.MessageView finishProposalWithoutAction(
            UUID sessionId, UUID executionId, long started, LlmModels.WorkflowDecision decision,
            Map<String, Object> results, PlanProposalService.Outcome proposal) {
        sessions.append(sessionId, "assistant", proposal.answer(), "COMPLETED", executionId,
                Map.of("rule", proposal.rule()));
        repository.finishExecution(executionId, "SUCCEEDED", elapsed(started), 0);
        metrics.agent("SUCCEEDED", proposal.degraded(), elapsed(started), 0);
        return view(executionId, decision, proposal.answer(), null,
                proposal.model(), proposal.degraded(), proposal.promptVersion(), results);
    }

    private void appendAssistant(UUID sessionId, UUID executionId, String answer, Map<String, Object> results) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (retrievalId(results) != null) metadata.put("retrievalId", retrievalId(results));
        if (!citations(results).isEmpty()) metadata.put("citations", citations(results));
        sessions.append(sessionId, "assistant", answer, "COMPLETED", executionId, metadata);
        repository.touchSession(sessionId);
    }

    private AgentDtos.MessageView view(UUID executionId, LlmModels.WorkflowDecision decision, String answer,
                                       AgentDtos.PendingActionView pending, String model, boolean degraded,
                                       String promptVersion, Map<String, Object> results) {
        return new AgentDtos.MessageView(executionId, decision.intent(), decision.tools(), answer,
                pending != null, pending, model, degraded, promptVersion, retrievalId(results), citations(results));
    }

    private String compose(String intent, Map<String, Object> results, boolean confirmation) {
        if (confirmation) {
            return "已结合画像、历史、PR、当前计划、训练量和知识库生成结构化计划草案。"
                    + "通过领域与安全规则，等待你明确确认后才会保存。";
        }
        long available = results.values().stream()
                .filter(value -> !(value instanceof Map<?, ?> map)
                        || !Boolean.FALSE.equals(map.get("available")))
                .count();
        return "已完成 " + intent + " 分析，调用 " + results.size()
                + " 个只读工具，其中 " + available + " 个返回有效结果。详细数据已保留在工具调用审计中。";
    }

    private List<RagDtos.Citation> citations(Map<String, Object> results) {
        Object value = results.get("search_knowledge");
        if (!(value instanceof RagDtos.SearchResponse response)) return List.of();
        return response.contexts().stream().map(RagDtos.RetrievedContext::citation)
                .filter(Objects::nonNull).distinct().toList();
    }

    private UUID retrievalId(Map<String, Object> results) {
        Object value = results.get("search_knowledge");
        return value instanceof RagDtos.SearchResponse response ? response.retrievalId() : null;
    }

    private void observeUser(long userId) {
        if (observations.getCurrentObservation() != null) {
            observations.getCurrentObservation().highCardinalityKeyValue(
                    "enduser.id", pendingActions.subjectHash(userId));
        }
    }

    private void requireOwnedSession(long userId, UUID sessionId) {
        if (!repository.ownsSession(sessionId, userId)) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND,
                    "agent session not found", HttpStatus.NOT_FOUND);
        }
    }

    private void fail(UUID executionId, UUID sessionId, long started) {
        repository.finishExecution(executionId, "FAILED", elapsed(started), 0);
        sessions.append(sessionId, "assistant", "本次响应失败，请稍后重试。", "ERROR", executionId, Map.of());
        metrics.agent("FAILED", true, elapsed(started), 0);
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}

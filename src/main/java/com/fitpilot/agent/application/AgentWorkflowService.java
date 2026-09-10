package com.fitpilot.agent.application;

import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.agent.memory.AgentSessionStore;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Stable application facade used by HTTP and MCP adapters. */
@Service
public class AgentWorkflowService {
    private final AgentRepository repository;
    private final AgentSessionStore sessions;
    private final AgentOrchestrator orchestrator;
    private final ToolExecutionService toolExecution;
    private final PendingActionService pendingActions;
    private final ConfirmationService confirmations;

    public AgentWorkflowService(AgentRepository repository, AgentSessionStore sessions,
                                AgentOrchestrator orchestrator, ToolExecutionService toolExecution,
                                PendingActionService pendingActions, ConfirmationService confirmations) {
        this.repository = repository;
        this.sessions = sessions;
        this.orchestrator = orchestrator;
        this.toolExecution = toolExecution;
        this.pendingActions = pendingActions;
        this.confirmations = confirmations;
    }

    public AgentDtos.SessionView createSession(long userId) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        repository.createSession(id, userId, now);
        return new AgentDtos.SessionView(id, now);
    }

    public List<AgentSessionStore.Message> messages(long userId, UUID sessionId) {
        requireOwnedSession(userId, sessionId);
        return sessions.messages(sessionId);
    }

    public PageResult<AgentDtos.SessionSummary> sessions(long userId, String status, long page, long size) {
        return repository.sessions(userId, status, page, size);
    }

    public AgentDtos.MessagePage history(long userId, UUID sessionId, Long beforeId, int limit) {
        requireAnyOwnedSession(userId, sessionId);
        return sessions.history(sessionId, beforeId, limit);
    }

    public void updateSession(long userId, UUID sessionId, AgentDtos.SessionUpdateRequest request) {
        if (!repository.updateSession(sessionId, userId, request.title(), request.status())) throw sessionNotFound();
    }

    public void deleteSession(long userId, UUID sessionId) {
        if (!repository.deleteSession(sessionId, userId)) throw sessionNotFound();
    }

    public List<AgentDtos.PendingActionSummary> pendingActions(long userId, UUID sessionId) {
        requireAnyOwnedSession(userId, sessionId);
        return pendingActions.list(userId, sessionId);
    }

    public AgentDtos.ConfirmationTokenView rotateConfirmationToken(long userId, UUID actionId) {
        return pendingActions.rotateToken(userId, actionId);
    }

    public AgentDtos.MessageView message(long userId, UUID sessionId, AgentDtos.MessageRequest request) {
        return orchestrator.message(userId, sessionId, request);
    }

    public Object confirm(long userId, UUID actionId, String token) {
        return confirmations.confirm(userId, actionId, token);
    }

    public void savePreference(long userId, AgentDtos.PreferenceRequest request) {
        repository.upsertMemory(userId, request.key(), request.value());
    }

    public List<AgentDtos.PreferenceView> preferences(long userId) {
        return repository.memories(userId);
    }

    public Object mcpTool(long userId, String tool, String query, TrainingPlanDtos.CreateRequest proposal) {
        AgentDtos.SessionView session = createSession(userId);
        if ("create_training_plan".equals(tool)) {
            return message(userId, session.id(), new AgentDtos.MessageRequest("创建训练计划", proposal));
        }
        return toolExecution.executeMcp(UUID.randomUUID(), userId, session.id(), tool, query);
    }

    private void requireOwnedSession(long userId, UUID sessionId) {
        if (!repository.ownsSession(sessionId, userId)) throw sessionNotFound();
    }

    private void requireAnyOwnedSession(long userId, UUID sessionId) {
        if (!repository.ownsAnySession(sessionId, userId)) throw sessionNotFound();
    }

    private BusinessException sessionNotFound() {
        return new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND,
                "agent session not found", HttpStatus.NOT_FOUND);
    }
}

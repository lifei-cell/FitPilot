package com.fitpilot.agent.application;

import com.fitpilot.agent.adjustment.TrainingAdjustmentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.agent.infrastructure.AgentRepository.PendingDecision;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ConfirmationService {
    private final AgentRepository repository;
    private final PendingActionService pendingActions;
    private final TrainingPlanGuardrail guardrail;
    private final TrainingPlanService plans;
    private final TrainingAdjustmentWorkflow adjustments;

    public ConfirmationService(AgentRepository repository, PendingActionService pendingActions,
                               TrainingPlanGuardrail guardrail, TrainingPlanService plans,
                               TrainingAdjustmentWorkflow adjustments) {
        this.repository = repository;
        this.pendingActions = pendingActions;
        this.guardrail = guardrail;
        this.plans = plans;
        this.adjustments = adjustments;
    }

    @Transactional(noRollbackFor = TrainingAdjustmentWorkflow.SourceChanged.class)
    public Object confirm(long userId, UUID actionId, String token) {
        AgentRepository.Pending pending = pendingActions.requireConfirmable(userId, actionId, token);
        if (TrainingAdjustmentWorkflow.TOOL.equals(pending.tool())) {
            TrainingAdjustmentDtos.AdjustmentProposal proposal = repository.read(
                    pending.payload(), TrainingAdjustmentDtos.AdjustmentProposal.class);
            validate(proposal.plan());
            return execute(userId, pending, actionId, proposal, () -> adjustments.confirm(userId, actionId, proposal));
        }
        TrainingPlanDtos.CreateRequest proposal = repository.read(
                pending.payload(), TrainingPlanDtos.CreateRequest.class);
        validate(proposal);
        return execute(userId, pending, actionId, proposal, () -> plans.create(userId, proposal));
    }

    private Object execute(long userId, AgentRepository.Pending pending, UUID actionId, Object proposal, Action action) {
        long started = System.nanoTime();
        Object result = action.execute();
        pendingActions.decide(userId, actionId, PendingDecision.EXECUTED);
        repository.toolCall(pending.executionId(), pending.tool(), proposal, result,
                "SUCCEEDED", elapsed(started));
        return result;
    }

    private void validate(TrainingPlanDtos.CreateRequest proposal) {
        List<String> issues = guardrail.validate(proposal);
        if (!issues.isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_GUARDRAIL_REJECTED,
                    String.join("; ", issues), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @FunctionalInterface
    private interface Action {
        Object execute();
    }
}

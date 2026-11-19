package com.fitpilot.agent.application;

import com.fitpilot.agent.adjustment.TrainingAdjustmentDtos.AdjustmentProposal;
import com.fitpilot.agent.adjustment.TrainingAdjustmentRepository;
import com.fitpilot.agent.adjustment.TrainingAdjustmentRepository.Decision;
import com.fitpilot.agent.dto.AgentDtos.PendingActionView;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.agent.infrastructure.AgentRepository.Pending;
import com.fitpilot.agent.infrastructure.AgentRepository.PendingDecision;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos.PlanView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TrainingAdjustmentWorkflow {
    static final String TOOL = "adjust_training_plan";

    private final TrainingAdjustmentRepository adjustments;
    private final AgentRepository actions;
    private final PendingActionService pendingActions;
    private final TrainingPlanService plans;

    public TrainingAdjustmentWorkflow(TrainingAdjustmentRepository adjustments, AgentRepository actions,
                                      PendingActionService pendingActions, TrainingPlanService plans) {
        this.adjustments = adjustments;
        this.actions = actions;
        this.pendingActions = pendingActions;
        this.plans = plans;
    }

    @Transactional
    public PendingActionView create(UUID executionId, long userId, AdjustmentProposal proposal,
                                    String model, boolean degraded, String promptVersion) {
        if (hasLivePredecessor(userId, proposal.sourcePlanId())) {
            throw new AlreadyPending();
        }
        PendingActionView pending = pendingActions.create(executionId, userId, TOOL,
                proposal, proposal.plan(), proposal.reasons());
        if (!adjustments.create(proposal, userId, pending.id(), model, degraded, promptVersion)) {
            throw new AlreadyPending();
        }
        return pending;
    }

    // Confirmation and rejection acquire the action row before changing the proposal.
    @Transactional
    public void reject(long userId, UUID adjustmentId) {
        UUID actionId = adjustments.findPendingAction(userId, adjustmentId);
        if (!isAwaiting(lockAdjustmentAction(userId, actionId))) {
            throw alreadyProcessed();
        }
        decideProposal(userId, adjustmentId, Decision.REJECTED, null);
        pendingActions.decide(userId, actionId, PendingDecision.REJECTED);
    }

    // Called inside ConfirmationService's transaction with the owned action already locked.
    PlanView confirm(long userId, UUID actionId, AdjustmentProposal proposal) {
        UUID adjustmentId = proposal.adjustmentId();
        if (!actionId.equals(adjustments.findPendingAction(userId, adjustmentId))) {
            throw alreadyProcessed();
        }
        if (!plans.isCurrentActiveVersion(userId, proposal.sourcePlanId(), proposal.sourcePlanVersion())) {
            decideProposal(userId, adjustmentId, Decision.STALE, null);
            pendingActions.decide(userId, actionId, PendingDecision.STALE);
            throw new SourceChanged();
        }
        PlanView draft = plans.create(userId, proposal.plan());
        decideProposal(userId, adjustmentId, Decision.ACCEPTED, draft.id());
        return draft;
    }

    private boolean hasLivePredecessor(long userId, long sourcePlanId) {
        var previous = adjustments.findPendingForSource(userId, sourcePlanId);
        if (previous == null) {
            return false;
        }
        Pending action = lockAdjustmentAction(userId, previous.actionId());
        boolean awaiting = isAwaiting(action);
        if (awaiting && !action.expiresAt().isBefore(Instant.now())) {
            return true;
        }
        if (adjustments.decide(userId, previous.id(), Decision.STALE, null) && awaiting) {
            pendingActions.decide(userId, previous.actionId(), PendingDecision.STALE);
        }
        return false;
    }

    private Pending lockAdjustmentAction(long userId, UUID actionId) {
        return actionId == null ? null : actions.lockPending(actionId, userId)
                .filter(action -> TOOL.equals(action.tool())).orElse(null);
    }

    private boolean isAwaiting(Pending action) {
        return action != null && "AWAITING_CONFIRMATION".equals(action.status());
    }

    private void decideProposal(long userId, UUID adjustmentId, Decision decision, Long draftPlanId) {
        if (!adjustments.decide(userId, adjustmentId, decision, draftPlanId)) {
            throw alreadyProcessed();
        }
    }

    private BusinessException alreadyProcessed() {
        return new BusinessException(ErrorCode.AGENT_ACTION_ALREADY_PROCESSED,
                "adjustment already processed or not found", HttpStatus.CONFLICT);
    }

    public static final class AlreadyPending extends RuntimeException {}

    static final class SourceChanged extends BusinessException {
        SourceChanged() {
            super(ErrorCode.PLAN_ADJUSTMENT_STALE, "active plan changed; regenerate the adjustment",
                    HttpStatus.CONFLICT);
        }
    }
}

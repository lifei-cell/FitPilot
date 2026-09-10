package com.fitpilot.agent.application;

import com.fitpilot.agent.adjustment.TrainingAdjustmentDtos;
import com.fitpilot.agent.adjustment.TrainingAdjustmentService;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.llm.application.LlmGateway;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds and validates write proposals without executing the confirmed business mutation. */
@Service
public class PlanProposalService {
    private final LlmGateway llm;
    private final IntentRouter intentRouter;
    private final PendingActionService pendingActions;
    private final TrainingPlanGuardrail guardrail;
    private final TrainingPlanService plans;
    private final TrainingAdjustmentService adjustments;
    private final AgentRepository repository;

    public PlanProposalService(LlmGateway llm, IntentRouter intentRouter,
                               PendingActionService pendingActions, TrainingPlanGuardrail guardrail,
                               TrainingPlanService plans, TrainingAdjustmentService adjustments,
                               AgentRepository repository) {
        this.llm = llm;
        this.intentRouter = intentRouter;
        this.pendingActions = pendingActions;
        this.guardrail = guardrail;
        this.plans = plans;
        this.adjustments = adjustments;
        this.repository = repository;
    }

    public Outcome prepare(long userId, AgentDtos.MessageRequest request, UUID executionId,
                           LlmModels.WorkflowDecision decision, Map<String, Object> results,
                           String model, boolean degraded, String promptVersion,
                           boolean hasCitations) {
        if (decision.tools().contains("create_training_plan")) {
            return prepareCreation(userId, request, executionId, results, model, degraded, promptVersion);
        }
        if (decision.tools().contains("adjust_training_plan")) {
            return prepareAdjustment(userId, request, executionId, results,
                    model, degraded, promptVersion, hasCitations);
        }
        return Outcome.none(model, degraded, promptVersion);
    }

    private Outcome prepareCreation(long userId, AgentDtos.MessageRequest request, UUID executionId,
                                    Map<String, Object> results, String model,
                                    boolean degraded, String promptVersion) {
        LlmModels.Result<TrainingPlanDtos.CreateRequest> planResult = request.proposedPlan() != null
                ? new LlmModels.Result<>(request.proposedPlan(), model, degraded,
                promptVersion, 0, 0, BigDecimal.ZERO)
                : llm.generatePlan(executionId, request.message(), results, defaultProposal(userId));
        intentRouter.recordUsage(executionId, planResult);
        TrainingPlanDtos.CreateRequest proposal = planResult.value();
        List<String> issues = guardrail.validate(proposal);
        if (!issues.isEmpty()) {
            repository.toolCall(executionId, "create_training_plan", proposal,
                    Map.of("violations", issues), "REJECTED", 0);
            return Outcome.rejected("计划草案未通过安全规则：" + String.join("；", issues),
                    issues.size(), planResult.model(), degraded || planResult.degraded(),
                    planResult.promptVersion());
        }
        AgentDtos.PendingActionView pending = pendingActions.create(executionId, userId,
                "create_training_plan", proposal, proposal, List.of());
        return Outcome.pending(pending, planResult.model(),
                degraded || planResult.degraded(), planResult.promptVersion());
    }

    private Outcome prepareAdjustment(long userId, AgentDtos.MessageRequest request, UUID executionId,
                                      Map<String, Object> results, String model,
                                      boolean degraded, String promptVersion, boolean hasCitations) {
        TrainingAdjustmentService.Analysis analysis = adjustments.analyze(userId);
        if (!analysis.proposalAllowed()) {
            adjustments.recordDecision(userId, analysis);
            return Outcome.completed(String.join("；", analysis.reasons()), analysis.rule(),
                    model, degraded, promptVersion);
        }
        if (adjustments.hasPending(userId, analysis.source().id())) {
            return Outcome.completed("已有一份待确认的计划调整，请先确认或拒绝后再重新生成。",
                    analysis.rule(), model, degraded, promptVersion);
        }

        TrainingPlanDtos.CreateRequest fallbackPlan = adjustments.deterministicPlan(analysis);
        LlmModels.Result<TrainingPlanDtos.CreateRequest> planResult = llm.generatePlan(
                executionId, request.message(), results, fallbackPlan);
        intentRouter.recordUsage(executionId, planResult);
        TrainingPlanDtos.CreateRequest plan = planResult.value();
        List<String> issues = adjustmentIssues(analysis, plan, hasCitations);
        if (!issues.isEmpty()) {
            plan = fallbackPlan;
            issues = adjustmentIssues(analysis, plan, false);
        }
        if (!issues.isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_GUARDRAIL_REJECTED,
                    String.join("; ", issues), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        TrainingAdjustmentDtos.AdjustmentProposal proposal = adjustments.proposal(analysis, plan);
        AgentDtos.PendingActionView pending = pendingActions.create(executionId, userId,
                "adjust_training_plan", proposal, plan, analysis.reasons());
        adjustments.record(userId, proposal, pending.id(), planResult.model(),
                degraded || planResult.degraded(), planResult.promptVersion());
        return Outcome.pending(pending, planResult.model(),
                degraded || planResult.degraded(), planResult.promptVersion());
    }

    private List<String> adjustmentIssues(TrainingAdjustmentService.Analysis analysis,
                                          TrainingPlanDtos.CreateRequest plan, boolean hasCitation) {
        List<String> issues = new ArrayList<>(guardrail.validate(plan));
        issues.addAll(adjustments.validate(analysis, plan, hasCitation));
        return issues;
    }

    private TrainingPlanDtos.CreateRequest defaultProposal(long userId) {
        try {
            var active = plans.active(userId);
            return new TrainingPlanDtos.CreateRequest(active.name() + " - Agent 草案",
                    "基于当前计划生成，确认后保存", active.goal(),
                    Math.min(active.durationWeeks() == null ? 8 : active.durationWeeks(), 16),
                    active.days().stream().map(day -> new TrainingPlanDtos.DayRequest(
                            day.dayNumber(), day.name(), day.notes(), day.exercises().stream().map(exercise ->
                            new TrainingPlanDtos.ExerciseRequest(exercise.exerciseId(), exercise.sequence(),
                                    exercise.targetSets(), exercise.targetRepsMin(), exercise.targetRepsMax(),
                                    exercise.targetRpe(), exercise.restSeconds(), exercise.notes())).toList())).toList());
        } catch (RuntimeException ignored) {
            int frequency = preferredFrequency(userId);
            List<TrainingPlanDtos.DayRequest> days = new ArrayList<>();
            for (int day = 1; day <= frequency; day++) {
                days.add(new TrainingPlanDtos.DayRequest(day, "全身训练 " + day, "Agent 基础草案",
                        List.of(exercise(1, 1), exercise(2, 2), exercise(3, 3))));
            }
            return new TrainingPlanDtos.CreateRequest("Agent 基础训练计划", "确认后仅保存为草稿",
                    "GENERAL_FITNESS", 8, days);
        }
    }

    private int preferredFrequency(long userId) {
        return repository.memories(userId).stream()
                .filter(memory -> "weekly_frequency".equals(memory.key()))
                .map(AgentDtos.PreferenceView::value)
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue)
                .map(value -> Math.max(2, Math.min(6, value)))
                .findFirst().orElse(3);
    }

    private TrainingPlanDtos.ExerciseRequest exercise(long id, int sequence) {
        return new TrainingPlanDtos.ExerciseRequest(
                id, sequence, 3, 8, 12, BigDecimal.valueOf(7), 90, null);
    }

    public enum State { NONE, PENDING, REJECTED, COMPLETED }

    public record Outcome(State state, AgentDtos.PendingActionView pending, String answer, String rule,
                          int violations, String model, boolean degraded, String promptVersion) {
        private static Outcome none(String model, boolean degraded, String promptVersion) {
            return new Outcome(State.NONE, null, null, null, 0, model, degraded, promptVersion);
        }

        private static Outcome pending(AgentDtos.PendingActionView pending, String model,
                                       boolean degraded, String promptVersion) {
            return new Outcome(State.PENDING, pending, null, null, 0, model, degraded, promptVersion);
        }

        private static Outcome rejected(String answer, int violations, String model,
                                        boolean degraded, String promptVersion) {
            return new Outcome(State.REJECTED, null, answer, null,
                    violations, model, degraded, promptVersion);
        }

        private static Outcome completed(String answer, String rule, String model,
                                         boolean degraded, String promptVersion) {
            return new Outcome(State.COMPLETED, null, answer, rule,
                    0, model, degraded, promptVersion);
        }
    }
}

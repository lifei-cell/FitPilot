package com.fitpilot.agent.adjustment;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.exercise.domain.Exercise;
import com.fitpilot.exercise.repository.ExerciseRepository;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TrainingAdjustmentService {
    private final TrainingAdjustmentRepository repository;
    private final TrainingPlanService plans;
    private final ExerciseRepository exercises;

    public TrainingAdjustmentService(TrainingAdjustmentRepository repository, TrainingPlanService plans,
                                     ExerciseRepository exercises) {
        this.repository = repository;
        this.plans = plans;
        this.exercises = exercises;
    }

    public Analysis analyze(long userId) {
        TrainingPlanDtos.PlanView source = plans.active(userId);
        TrainingAdjustmentRepository.Metrics metrics = repository.metrics(userId);
        double expected = Math.max(1, source.daysPerWeek() * 4d);
        double completion = ratio(metrics.completedWorkouts(), expected);
        double setCompletion = ratio(metrics.completedSets(), Math.max(1, metrics.targetSets()));
        double volumeChange = metrics.previousVolume() == 0 ? 0
                : (metrics.currentVolume() - metrics.previousVolume()) / metrics.previousVolume();
        var evidence = new TrainingAdjustmentDtos.Evidence(28, metrics.completedWorkouts(), completion,
                metrics.completedSets(), metrics.targetSets(), setCompletion, round(metrics.averageRpe()),
                metrics.feedbackCount(), round(metrics.averageFatigue()), metrics.latestPain(),
                round(metrics.currentVolume()), round(metrics.previousVolume()), round(volumeChange),
                metrics.personalRecords());
        List<String> reasons = new ArrayList<>();
        String rule;
        if (metrics.completedWorkouts() < 3 || metrics.feedbackCount() < 2) {
            rule = "INSUFFICIENT_DATA";
            reasons.add("至少需要 3 次完成训练和 2 份训练反馈");
        } else if (metrics.latestPain() >= 4) {
            rule = "SAFETY_HOLD";
            reasons.add("最近 7 天疼痛评分达到 4，暂停自动进阶并建议专业评估");
        } else if (metrics.averageFatigue() >= 8 || metrics.averageRpe() >= 9) {
            rule = "DELOAD";
            reasons.add("疲劳或平均工作组 RPE 偏高，建议降低 10%-20% 周训练量");
        } else if (completion < 0.7) {
            rule = "SIMPLIFY";
            reasons.add("最近 28 天计划完成率低于 70%，建议降低执行复杂度");
        } else if (completion >= 0.85 && metrics.averageFatigue() <= 6 && metrics.latestPain() <= 2
                && metrics.averageRpe() >= 6 && metrics.averageRpe() <= 8.5
                && metrics.personalRecords() == 0 && Math.abs(volumeChange) <= 0.05) {
            rule = "PROGRESS";
            reasons.add("完成率稳定且恢复良好，但容量和 PR 趋势进入平台期");
        } else {
            rule = "NO_CHANGE";
            reasons.add("当前负荷、完成率与恢复信号平衡，建议保持计划");
        }
        return new Analysis(source, evidence, rule, List.copyOf(reasons),
                List.of("DELOAD", "SIMPLIFY", "PROGRESS").contains(rule));
    }

    public TrainingAdjustmentDtos.Context context(long userId) {
        Analysis analysis = analyze(userId);
        return new TrainingAdjustmentDtos.Context(analysis.source().id(), analysis.source().version(), analysis.rule(),
                analysis.proposalAllowed(), analysis.evidence(), analysis.reasons());
    }

    public TrainingPlanDtos.CreateRequest deterministicPlan(Analysis analysis) {
        TrainingPlanDtos.PlanView source = analysis.source();
        List<TrainingPlanDtos.DayView> sourceDays = source.days();
        int keepDays = "SIMPLIFY".equals(analysis.rule()) && sourceDays.size() > 1 ? sourceDays.size() - 1 : sourceDays.size();
        List<TrainingPlanDtos.DayRequest> days = new ArrayList<>();
        int[] remainingSetReduction = {"DELOAD".equals(analysis.rule())
                ? Math.max(1, (int) Math.round(weeklySets(source) * 0.15)) : 0};
        for (int i = 0; i < keepDays; i++) {
            TrainingPlanDtos.DayView day = sourceDays.get(i);
            List<TrainingPlanDtos.ExerciseRequest> exercises = new ArrayList<>();
            for (TrainingPlanDtos.ExerciseView exercise : day.exercises()) {
                int sets = exercise.targetSets();
                int repsMax = exercise.targetRepsMax();
                if (remainingSetReduction[0] > 0 && sets > 1) {
                    int reduction = Math.min(sets - 1, remainingSetReduction[0]);
                    sets -= reduction;
                    remainingSetReduction[0] -= reduction;
                }
                if ("PROGRESS".equals(analysis.rule())) repsMax = Math.min(30, repsMax + 1);
                exercises.add(new TrainingPlanDtos.ExerciseRequest(exercise.exerciseId(), exercise.sequence(), sets,
                        exercise.targetRepsMin(), repsMax, exercise.targetRpe(), exercise.restSeconds(), exercise.notes()));
            }
            days.add(new TrainingPlanDtos.DayRequest(day.dayNumber(), day.name(), day.notes(), exercises));
        }
        return new TrainingPlanDtos.CreateRequest(source.name() + " · AI 调整草案",
                "基于最近 28 天训练事实生成；确认后仅保存为草稿", source.goal(),
                Math.min(source.durationWeeks() == null ? 8 : source.durationWeeks(), 16), days);
    }

    public List<String> validate(Analysis analysis, TrainingPlanDtos.CreateRequest proposal, boolean hasCitation) {
        List<String> issues = new ArrayList<>();
        int sourceSets = weeklySets(analysis.source());
        int proposedSets = proposal.days().stream().flatMap(day -> day.exercises().stream())
                .mapToInt(TrainingPlanDtos.ExerciseRequest::targetSets).sum();
        double delta = sourceSets == 0 ? 0 : (double) (proposedSets - sourceSets) / sourceSets;
        if ("DELOAD".equals(analysis.rule()) && (delta > -0.10 || delta < -0.20)) issues.add("减量草案的周组数必须下降 10%-20%");
        if ("SIMPLIFY".equals(analysis.rule())) {
            int removedDays = analysis.source().daysPerWeek() - proposal.days().size();
            if (removedDays < 0 || removedDays > 1) issues.add("单次调整最多减少一个训练日");
            if (removedDays == 0 && (delta > -0.10 || delta < -0.20)) issues.add("不减少训练日时，周组数必须下降 10%-20%");
        }
        if ("PROGRESS".equals(analysis.rule()) && (delta < 0 || delta > 0.10)) issues.add("进阶草案的周组数增幅必须位于 0%-10%");
        validateExerciseChanges(analysis.source(), proposal, hasCitation, issues);
        if (!analysis.proposalAllowed()) issues.add("当前分析结果不允许生成计划草案");
        return issues;
    }

    private void validateExerciseChanges(TrainingPlanDtos.PlanView source, TrainingPlanDtos.CreateRequest proposal,
                                         boolean hasCitation, List<String> issues) {
        var sourceSlots = new java.util.HashMap<String, Long>();
        source.days().forEach(day -> day.exercises().forEach(exercise ->
                sourceSlots.put(day.dayNumber() + ":" + exercise.sequence(), exercise.exerciseId())));
        for (TrainingPlanDtos.DayRequest day : proposal.days()) {
            for (TrainingPlanDtos.ExerciseRequest exercise : day.exercises()) {
                Long sourceExerciseId = sourceSlots.get(day.dayNumber() + ":" + exercise.sequence());
                if (sourceExerciseId == null) {
                    issues.add("调整草案不能新增未经验证的动作位置");
                } else if (!sourceExerciseId.equals(exercise.exerciseId())) {
                    if (!hasCitation) issues.add("动作替换必须引用有效知识来源");
                    Exercise previous = exercises.findActive(sourceExerciseId).orElse(null);
                    Exercise replacement = exercises.findActive(exercise.exerciseId()).orElse(null);
                    if (previous == null || replacement == null || previous.primaryMuscle == null
                            || !previous.primaryMuscle.equalsIgnoreCase(replacement.primaryMuscle)) {
                        issues.add("动作替换必须匹配原动作的主要肌群");
                    }
                }
            }
        }
    }

    public TrainingAdjustmentDtos.AdjustmentProposal proposal(Analysis analysis, TrainingPlanDtos.CreateRequest plan) {
        return new TrainingAdjustmentDtos.AdjustmentProposal(UUID.randomUUID(), analysis.source().id(),
                analysis.source().version(), analysis.rule(), analysis.evidence(), analysis.reasons(), plan);
    }

    public void record(long userId, TrainingAdjustmentDtos.AdjustmentProposal proposal, UUID pendingActionId,
                       String model, boolean degraded, String promptVersion) {
        repository.create(proposal, userId, pendingActionId, model, degraded, promptVersion);
    }
    public boolean hasPending(long userId, long sourcePlanId) { return repository.hasPending(userId, sourcePlanId); }

    public void recordDecision(long userId, Analysis analysis) {
        repository.createDecision(UUID.randomUUID(), userId, analysis.source().id(), analysis.source().version(),
                analysis.rule(), analysis.evidence(), analysis.reasons());
    }

    public TrainingPlanDtos.PlanView confirm(long userId, TrainingAdjustmentDtos.AdjustmentProposal proposal) {
        TrainingPlanDtos.PlanView current = plans.active(userId);
        if (current.id() != proposal.sourcePlanId() || current.version() != proposal.sourcePlanVersion()) {
            repository.stale(proposal.adjustmentId());
            throw new BusinessException(ErrorCode.PLAN_ADJUSTMENT_STALE,
                    "active plan changed; regenerate the adjustment", HttpStatus.CONFLICT);
        }
        TrainingPlanDtos.PlanView draft = plans.create(userId, proposal.plan());
        repository.accept(proposal.adjustmentId(), draft.id());
        return draft;
    }

    public PageResult<TrainingAdjustmentDtos.AdjustmentView> list(long userId, long page, long size) {
        return repository.list(userId, page, size);
    }
    public TrainingAdjustmentDtos.AdjustmentView get(long userId, UUID id) {
        TrainingAdjustmentDtos.AdjustmentView value = repository.find(id, userId);
        if (value == null) throw new BusinessException(ErrorCode.AGENT_ACTION_ALREADY_PROCESSED,
                "adjustment not found", HttpStatus.NOT_FOUND);
        return value;
    }

    public void reject(long userId, UUID adjustmentId) {
        if (!repository.reject(adjustmentId, userId)) {
            throw new BusinessException(ErrorCode.AGENT_ACTION_ALREADY_PROCESSED,
                    "adjustment already processed or not found", HttpStatus.CONFLICT);
        }
    }

    private int weeklySets(TrainingPlanDtos.PlanView plan) {
        return plan.days().stream().flatMap(day -> day.exercises().stream())
                .mapToInt(TrainingPlanDtos.ExerciseView::targetSets).sum();
    }
    private double ratio(double value, double total) { return round(Math.min(1, value / total)); }
    private double round(double value) { return Math.round(value * 10000d) / 10000d; }

    public record Analysis(TrainingPlanDtos.PlanView source, TrainingAdjustmentDtos.Evidence evidence,
                           String rule, List<String> reasons, boolean proposalAllowed) {}
}

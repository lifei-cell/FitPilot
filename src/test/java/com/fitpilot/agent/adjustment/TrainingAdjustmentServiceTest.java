package com.fitpilot.agent.adjustment;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import com.fitpilot.exercise.repository.ExerciseRepository;
import com.fitpilot.exercise.domain.Exercise;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TrainingAdjustmentServiceTest {
    private final TrainingAdjustmentRepository repository = mock(TrainingAdjustmentRepository.class);
    private final TrainingPlanService plans = mock(TrainingPlanService.class);
    private final ExerciseRepository exercises = mock(ExerciseRepository.class);
    private final TrainingAdjustmentService service = new TrainingAdjustmentService(repository, plans, exercises);

    @Test
    void blocksProgressionWhenRecentPainIsElevated() {
        when(plans.active(7)).thenReturn(plan(1));
        when(repository.metrics(7)).thenReturn(new TrainingAdjustmentRepository.Metrics(
                8, 70, 72, 7.5, 6, 5.0, 4, 10000, 10000, 0));

        var analysis = service.analyze(7);

        assertThat(analysis.rule()).isEqualTo("SAFETY_HOLD");
        assertThat(analysis.proposalAllowed()).isFalse();
    }

    @Test
    void createsADecreasedWorkloadWithinGuardrailBounds() {
        when(plans.active(7)).thenReturn(plan(1));
        when(repository.metrics(7)).thenReturn(new TrainingAdjustmentRepository.Metrics(
                8, 70, 72, 9.1, 6, 8.2, 1, 9000, 10000, 0));

        var analysis = service.analyze(7);
        var proposal = service.deterministicPlan(analysis);

        assertThat(analysis.rule()).isEqualTo("DELOAD");
        assertThat(service.validate(analysis, proposal, false)).isEmpty();
        assertThat(proposal.name()).contains("AI 调整草案");
    }

    @Test
    void rejectsConfirmationWhenActivePlanVersionChanged() {
        when(plans.active(7)).thenReturn(plan(2));
        var proposal = new TrainingAdjustmentDtos.AdjustmentProposal(java.util.UUID.randomUUID(), 42, 1,
                "PROGRESS", evidence(), List.of("平台期"), createRequest());

        assertThatThrownBy(() -> service.confirm(7, proposal)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("active plan changed");
        verify(repository).stale(proposal.adjustmentId());
        verify(plans, never()).create(anyLong(), any());
    }

    @Test
    void replacementRequiresCitationAndMatchingPrimaryMuscle() {
        when(plans.active(7)).thenReturn(plan(1));
        when(repository.metrics(7)).thenReturn(new TrainingAdjustmentRepository.Metrics(
                12, 80, 81, 7.2, 6, 5, 1, 10000, 10000, 0));
        Exercise source = exercise(1, "CHEST");
        Exercise replacement = exercise(99, "CHEST");
        when(exercises.findActive(1)).thenReturn(java.util.Optional.of(source));
        when(exercises.findActive(99)).thenReturn(java.util.Optional.of(replacement));
        var proposal = service.deterministicPlan(service.analyze(7));
        var firstDay = proposal.days().getFirst();
        var first = firstDay.exercises().getFirst();
        var changed = new TrainingPlanDtos.ExerciseRequest(99L, first.sequence(), first.targetSets(),
                first.targetRepsMin(), first.targetRepsMax(), first.targetRpe(), first.restSeconds(), first.notes());
        var changedPlan = new TrainingPlanDtos.CreateRequest(proposal.name(), proposal.description(), proposal.goal(),
                proposal.durationWeeks(), java.util.stream.IntStream.range(0, proposal.days().size()).mapToObj(index -> {
                    var day = proposal.days().get(index);
                    return index == 0 ? new TrainingPlanDtos.DayRequest(day.dayNumber(), day.name(), day.notes(),
                            java.util.stream.IntStream.range(0, day.exercises().size())
                                    .mapToObj(i -> i == 0 ? changed : day.exercises().get(i)).toList()) : day;
                }).toList());

        assertThat(service.validate(service.analyze(7), changedPlan, false))
                .contains("动作替换必须引用有效知识来源");
        assertThat(service.validate(service.analyze(7), changedPlan, true))
                .doesNotContain("动作替换必须引用有效知识来源", "动作替换必须匹配原动作的主要肌群");
    }

    private TrainingPlanDtos.PlanView plan(int version) {
        List<TrainingPlanDtos.DayView> days = java.util.stream.IntStream.rangeClosed(1, 3).mapToObj(day ->
                new TrainingPlanDtos.DayView(day, day, "训练 " + day, null,
                        java.util.stream.IntStream.rangeClosed(1, 3).mapToObj(exercise ->
                                new TrainingPlanDtos.ExerciseView(day * 10L + exercise, exercise, "动作", exercise,
                                        3, 8, 12, BigDecimal.valueOf(7.5), 90, null)).toList())).toList();
        return new TrainingPlanDtos.PlanView(42, "力量计划", null, "STRENGTH", 8, 3, "ACTIVE", version,
                LocalDate.now().minusWeeks(4), null, days);
    }

    private TrainingAdjustmentDtos.Evidence evidence() {
        return new TrainingAdjustmentDtos.Evidence(28, 8, .9, 70, 72, .97, 7.5, 6, 5, 1,
                10000, 10000, 0, 0);
    }

    private TrainingPlanDtos.CreateRequest createRequest() {
        return new TrainingPlanDtos.CreateRequest("草案", null, "STRENGTH", 8, List.of(
                new TrainingPlanDtos.DayRequest(1, "训练", null, List.of(
                        new TrainingPlanDtos.ExerciseRequest(1L, 1, 3, 8, 12, BigDecimal.valueOf(7), 90, null)))));
    }

    private Exercise exercise(long id, String muscle) {
        Exercise exercise = new Exercise();
        exercise.id = id;
        exercise.primaryMuscle = muscle;
        exercise.status = 1;
        return exercise;
    }
}

package com.fitpilot.agent.adjustment;

import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import com.fitpilot.exercise.application.ExerciseService;
import com.fitpilot.exercise.dto.ExerciseView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TrainingAdjustmentServiceTest {
    private final TrainingAdjustmentRepository repository = mock(TrainingAdjustmentRepository.class);
    private final TrainingPlanService plans = mock(TrainingPlanService.class);
    private final ExerciseService exercises = mock(ExerciseService.class);
    private final TrainingAdjustmentService service = new TrainingAdjustmentService(repository, plans, exercises);

    @Test
    void blocksProgressionWhenRecentPainIsElevated() {
        when(plans.active(7)).thenReturn(plan(1));
        when(repository.metrics(7)).thenReturn(new TrainingAdjustmentRepository.Metrics(
                8, 70, 72, 7.5, 6, 5.0, 4, 10000, 10000, 0, false));

        var analysis = service.analyze(7);

        assertThat(analysis.rule()).isEqualTo("SAFETY_HOLD");
        assertThat(analysis.proposalAllowed()).isFalse();
    }

    @Test
    void createsADecreasedWorkloadWithinGuardrailBounds() {
        when(plans.active(7)).thenReturn(plan(1));
        when(repository.metrics(7)).thenReturn(new TrainingAdjustmentRepository.Metrics(
                8, 70, 72, 9.1, 6, 8.2, 1, 9000, 10000, 0, false));

        var analysis = service.analyze(7);
        var proposal = service.deterministicPlan(analysis);

        assertThat(analysis.rule()).isEqualTo("DELOAD");
        assertThat(service.validate(analysis, proposal, false)).isEmpty();
        assertThat(proposal.name()).contains("AI 调整草案");
    }

    @ParameterizedTest
    @CsvSource({"0, true, 前一个 14 天窗口", "10000, false, 个人纪录尚未核实"})
    void declinesProgressionWithoutComparableVolumeAndCompletePrProjection(
            double previousVolume, boolean prComplete, String reason) {
        when(plans.active(7)).thenReturn(plan(1));
        when(repository.metrics(7)).thenReturn(new TrainingAdjustmentRepository.Metrics(
                12, 80, 81, 7.2, 6, 5, 1, 10000, previousVolume, 0, prComplete));

        var analysis = service.analyze(7);

        assertThat(analysis.rule()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(analysis.proposalAllowed()).isFalse();
        assertThat(analysis.reasons()).singleElement().asString().contains(reason);
    }

    @Test
    void replacementRequiresCitationAndMatchingPrimaryMuscle() {
        when(plans.active(7)).thenReturn(plan(1));
        when(repository.metrics(7)).thenReturn(new TrainingAdjustmentRepository.Metrics(
                12, 80, 81, 7.2, 6, 5, 1, 10000, 10000, 0, true));
        ExerciseView source = exercise(1, "CHEST");
        ExerciseView replacement = exercise(99, "CHEST");
        when(exercises.findActive(1)).thenReturn(java.util.Optional.of(source));
        when(exercises.findActive(99)).thenReturn(java.util.Optional.of(replacement));
        var analysis = service.analyze(7);
        assertThat(analysis.rule()).isEqualTo("PROGRESS");
        assertThat(analysis.proposalAllowed()).isTrue();
        var proposal = service.deterministicPlan(analysis);
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

        assertThat(service.validate(analysis, changedPlan, false))
                .contains("动作替换必须引用有效知识来源");
        assertThat(service.validate(analysis, changedPlan, true))
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

    private ExerciseView exercise(long id, String muscle) {
        return new ExerciseView(id, "exercise", null, "STRENGTH", null,
                null, muscle, null, null, null);
    }
}

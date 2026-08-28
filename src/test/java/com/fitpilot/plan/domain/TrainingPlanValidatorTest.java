package com.fitpilot.plan.domain;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingPlanValidatorTest {
    @Test
    void rejectsDuplicateDayNumbers() {
        var exercise = new TrainingPlanDtos.ExerciseRequest(1L, 1, 3, 5, 8,
                new BigDecimal("8"), 120, null);
        var request = new TrainingPlanDtos.CreateRequest("plan", null, "STRENGTH", 8,
                List.of(new TrainingPlanDtos.DayRequest(1, "A", null, List.of(exercise)),
                        new TrainingPlanDtos.DayRequest(1, "B", null, List.of(exercise))));
        assertThatThrownBy(() -> TrainingPlanValidator.validate(request)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsInvertedRepRange() {
        var exercise = new TrainingPlanDtos.ExerciseRequest(1L, 1, 3, 10, 5,
                new BigDecimal("8"), 120, null);
        var request = new TrainingPlanDtos.CreateRequest("plan", null, "STRENGTH", 8,
                List.of(new TrainingPlanDtos.DayRequest(1, "A", null, List.of(exercise))));
        assertThatThrownBy(() -> TrainingPlanValidator.validate(request)).isInstanceOf(BusinessException.class);
    }
}

package com.fitpilot.plan.domain;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.plan.dto.TrainingPlanDtos;

import java.util.HashSet;

public final class TrainingPlanValidator {
    private TrainingPlanValidator() {}

    public static void validate(TrainingPlanDtos.CreateRequest request) {
        validateDays(request.days());
    }

    public static void validate(TrainingPlanDtos.UpdateRequest request) {
        validateDays(request.days());
    }

    private static void validateDays(java.util.List<TrainingPlanDtos.DayRequest> days) {
        var dayNumbers = new HashSet<Integer>();
        for (var day : days) {
            if (!dayNumbers.add(day.dayNumber())) invalid("dayNumber must be unique");
            var sequences = new HashSet<Integer>();
            for (var exercise : day.exercises()) {
                if (!sequences.add(exercise.sequence())) invalid("exercise sequence must be unique within a day");
                if (exercise.targetRepsMin() > exercise.targetRepsMax()) invalid("targetRepsMin must not exceed targetRepsMax");
            }
        }
    }

    private static void invalid(String message) {
        throw new BusinessException(ErrorCode.INVALID_TRAINING_PLAN, message);
    }
}

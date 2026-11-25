package com.fitpilot.agent.application;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.plan.application.TrainingPlanValidationService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class TrainingPlanGuardrail {
    private final TrainingPlanValidationService validation;
    private final Validator validator;

    public TrainingPlanGuardrail(TrainingPlanValidationService validation, Validator validator) {
        this.validation = validation;
        this.validator = validator;
    }

    public List<String> validate(TrainingPlanDtos.CreateRequest plan) {
        if (plan == null) return List.of("缺少计划草案");
        // Model output and stored pending actions bypass controller-level @Valid.
        var fields = validator.validate(plan).stream()
                .map(issue -> issue.getPropertyPath() + " " + issue.getMessage())
                .sorted().toList();
        if (!fields.isEmpty()) return fields;

        List<String> violations = new ArrayList<>();
        try { validation.validate(plan); }
        catch (BusinessException e) { violations.add(e.getMessage()); }
        if (plan.durationWeeks() == null || plan.durationWeeks() > 16) violations.add("计划周期必须为 1-16 周");
        if (plan.days().size() > 6) violations.add("每周训练频率不得超过 6 天");
        int weeklySets = 0;
        for (var day : plan.days()) {
            if (day.exercises().size() > 10) violations.add("单日动作数必须为 1-10 个");
            for (var exercise : day.exercises()) {
                weeklySets += exercise.targetSets();
                if (exercise.targetSets() > 8) violations.add("单动作组数必须为 1-8 组");
                if (exercise.targetRepsMax() > 30) violations.add("目标次数不得超过 30");
                if (exercise.targetRpe() != null && exercise.targetRpe().compareTo(BigDecimal.valueOf(5)) < 0)
                    violations.add("目标 RPE 不得低于 5");
                if (exercise.restSeconds() != null && (exercise.restSeconds() < 30 || exercise.restSeconds() > 600))
                    violations.add("组间休息必须为 30-600 秒");
            }
        }
        if (weeklySets > 120) violations.add("每周总组数不得超过 120");
        return violations.stream().distinct().toList();
    }
}

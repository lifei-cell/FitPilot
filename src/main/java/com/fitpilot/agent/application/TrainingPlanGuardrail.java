package com.fitpilot.agent.application;

import com.fitpilot.plan.domain.TrainingPlanValidator;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class TrainingPlanGuardrail {
    public List<String> validate(TrainingPlanDtos.CreateRequest plan) {
        List<String> violations = new ArrayList<>();
        try { TrainingPlanValidator.validate(plan); }
        catch (RuntimeException e) { violations.add(e.getMessage()); }
        if (plan.durationWeeks() == null || plan.durationWeeks() > 16) violations.add("计划周期必须为 1-16 周");
        if (plan.days() == null || plan.days().size() > 6) violations.add("每周训练频率不得超过 6 天");
        int weeklySets = 0;
        if (plan.days() != null) for (var day : plan.days()) {
            if (day.exercises() == null || day.exercises().size() > 10) violations.add("单日动作数必须为 1-10 个");
            if (day.exercises() != null) for (var exercise : day.exercises()) {
                if (exercise.targetSets() != null) weeklySets += exercise.targetSets();
                if (exercise.targetSets() == null || exercise.targetSets() > 8) violations.add("单动作组数必须为 1-8 组");
                if (exercise.targetRepsMax() != null && exercise.targetRepsMax() > 30) violations.add("目标次数不得超过 30");
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

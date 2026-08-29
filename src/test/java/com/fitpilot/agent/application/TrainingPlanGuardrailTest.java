package com.fitpilot.agent.application;

import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingPlanGuardrailTest {
    private final TrainingPlanGuardrail guardrail=new TrainingPlanGuardrail();
    @Test void acceptsBoundedStructuredPlan(){assertThat(guardrail.validate(plan(3,8,7,90,3))).isEmpty();}
    @Test void rejectsExcessiveVolumeRpeRestAndDuration(){
        assertThat(guardrail.validate(plan(9,20,4,900,9))).contains("计划周期必须为 1-16 周","每周训练频率不得超过 6 天","单动作组数必须为 1-8 组","目标 RPE 不得低于 5","组间休息必须为 30-600 秒");
    }
    private TrainingPlanDtos.CreateRequest plan(int days,int weeks,int rpe,int rest,int sets){
        return new TrainingPlanDtos.CreateRequest("test",null,"GENERAL_FITNESS",weeks,
                java.util.stream.IntStream.rangeClosed(1,days).mapToObj(day->new TrainingPlanDtos.DayRequest(day,"day",null,List.of(
                        new TrainingPlanDtos.ExerciseRequest(1L,1,sets,8,12,BigDecimal.valueOf(rpe),rest,null)))).toList());
    }
}

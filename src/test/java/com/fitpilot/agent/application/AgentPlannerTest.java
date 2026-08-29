package com.fitpilot.agent.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPlannerTest {
    private final AgentPlanner planner=new AgentPlanner();
    @Test void selectsExactToolsForAcceptanceDataset(){
        List<Case> dataset=List.of(
                new Case("查看我的用户画像",List.of("get_user_profile")),
                new Case("最近训练历史和表现如何",List.of("get_workout_history","get_personal_records","get_training_volume")),
                new Case("我的 PR 是什么",List.of("get_personal_records")),
                new Case("本周训练量",List.of("get_training_volume")),
                new Case("当前训练计划",List.of("get_training_plan")),
                new Case("深蹲动作怎么练",List.of("search_knowledge")),
                new Case("帮我制定新计划",List.of("get_user_profile","get_workout_history","get_personal_records","get_training_plan","get_training_volume","search_knowledge","create_training_plan")));
        long correct=dataset.stream().filter(c->planner.decide(c.query()).tools().equals(c.tools())).count();
        assertThat((double)correct/dataset.size()).isEqualTo(1.0);
    }
    record Case(String query,List<String> tools){}
}

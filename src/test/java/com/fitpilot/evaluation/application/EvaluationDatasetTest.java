package com.fitpilot.evaluation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.application.AgentPlanner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationDatasetTest {
    @Test void datasetsMeetV5SizeAndRuleWorkflowGate(){
        EvaluationDatasetLoader loader=new EvaluationDatasetLoader(new ObjectMapper());
        var agent=loader.agent();var rag=loader.rag();AgentPlanner planner=new AgentPlanner();
        assertThat(agent).hasSizeGreaterThanOrEqualTo(150);assertThat(rag).hasSizeGreaterThanOrEqualTo(50);
        long correct=agent.stream().filter(item->planner.decide(item.query()).tools().equals(item.expectedTools())).count();
        assertThat((double)correct/agent.size()).isGreaterThanOrEqualTo(0.95);
        assertThat(agent).allMatch(item->item.forbiddenTools().stream().noneMatch(planner.decide(item.query()).tools()::contains));
    }
}

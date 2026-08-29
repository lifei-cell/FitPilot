package com.fitpilot.llm.application;

import com.fitpilot.llm.config.LlmProperties;
import com.fitpilot.llm.domain.LlmModels;
import org.springframework.stereotype.Component;

@Component
public class ModelRouter {
    public String model(LlmProperties.Endpoint endpoint, LlmModels.Task task) {
        return switch (task) {
            case INTENT_CLASSIFICATION, QUERY_REWRITE, MEMORY_EXTRACTION -> endpoint.getSmallModel();
            case TRAINING_ANALYSIS -> endpoint.getMediumModel();
            case PLAN_GENERATION -> endpoint.getStrongModel();
        };
    }
}

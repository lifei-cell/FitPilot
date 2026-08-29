package com.fitpilot.llm.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.application.AgentPlanner;
import com.fitpilot.llm.config.LlmProperties;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.llm.infrastructure.OpenAiCompatibleClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmGatewayTest {
    private final LlmProperties properties = enabled();
    private final PromptRegistry prompts = new PromptRegistry(properties);
    private final OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
    private final LlmGateway gateway = new LlmGateway(properties,prompts,client,new ObjectMapper());

    @Test void acceptsOnlyAllowedStructuredDecision() {
        when(client.complete(any(),eq(LlmModels.Task.INTENT_CLASSIFICATION),anyString(),eq(true)))
                .thenReturn(completion("{\"intent\":\"TRAINING_VOLUME\",\"toolCalls\":[{\"name\":\"get_training_volume\",\"arguments\":{}}],\"responseMode\":\"analysis\"}"));
        var fallback=new AgentPlanner.Decision("PROFILE",List.of("get_user_profile"));
        var result=gateway.decide(java.util.UUID.randomUUID(),"训练量",fallback);
        assertThat(result.value().tools()).containsExactly("get_training_volume");
        assertThat(result.degraded()).isFalse();
    }
    @Test void rejectsIdentityArgumentsAndFallsBackToRules() {
        when(client.complete(any(),eq(LlmModels.Task.INTENT_CLASSIFICATION),anyString(),eq(true)))
                .thenReturn(completion("{\"intent\":\"PROFILE\",\"toolCalls\":[{\"name\":\"get_user_profile\",\"arguments\":{\"userId\":99}}],\"responseMode\":\"analysis\"}"));
        var fallback=new AgentPlanner.Decision("PROFILE",List.of("get_user_profile"));
        var result=gateway.decide(java.util.UUID.randomUUID(),"越权",fallback);
        assertThat(result.value()).isEqualTo(fallback);
        assertThat(result.model()).isEqualTo("RULE_WORKFLOW");
        assertThat(result.degraded()).isTrue();
    }
    private LlmModels.Completion completion(String content){return new LlmModels.Completion(content,"primary","small",10,5,BigDecimal.ZERO,false);}
    private LlmProperties enabled(){LlmProperties value=new LlmProperties();value.setEnabled(true);return value;}
}

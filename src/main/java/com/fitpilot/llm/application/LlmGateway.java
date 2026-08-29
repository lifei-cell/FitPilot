package com.fitpilot.llm.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.application.AgentPlanner;
import com.fitpilot.llm.config.LlmProperties;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.llm.infrastructure.OpenAiCompatibleClient;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class LlmGateway {
    public static final List<String> PLAN_TOOLS = List.of("get_user_profile", "get_workout_history",
            "get_personal_records", "get_training_plan", "get_training_volume", "search_knowledge", "create_training_plan");
    public static final Set<String> READ_TOOLS = Set.of("get_user_profile", "get_workout_history",
            "get_personal_records", "get_training_plan", "get_training_volume", "search_knowledge");
    private final LlmProperties properties; private final PromptRegistry prompts;
    private final OpenAiCompatibleClient client; private final ObjectMapper json;
    public LlmGateway(LlmProperties properties, PromptRegistry prompts, OpenAiCompatibleClient client, ObjectMapper json) {
        this.properties=properties;this.prompts=prompts;this.client=client;this.json=json;
    }

    public LlmModels.Result<AgentPlanner.Decision> decide(UUID executionId, String message, AgentPlanner.Decision fallback) {
        if (!properties.isEnabled()) return LlmModels.Result.rule(fallback,prompts.version());
        try {
            LlmModels.Completion completion=client.complete(executionId,LlmModels.Task.INTENT_CLASSIFICATION,message,true);
            LlmModels.AgentDecision raw=json.readValue(cleanJson(completion.content()),LlmModels.AgentDecision.class);
            AgentPlanner.Decision decision=validate(raw);
            return result(decision,completion);
        } catch (Exception failure) { return LlmModels.Result.rule(fallback,prompts.version()); }
    }
    public LlmModels.Result<TrainingPlanDtos.CreateRequest> generatePlan(UUID executionId,String message,Object context,
                                                                          TrainingPlanDtos.CreateRequest fallback) {
        if (!properties.isEnabled()) return LlmModels.Result.rule(fallback,prompts.version());
        try {
            String prompt="USER_REQUEST:\n"+message+"\nSERVER_TOOL_CONTEXT:\n"+boundedJson(context);
            LlmModels.Completion completion=client.complete(executionId,LlmModels.Task.PLAN_GENERATION,prompt,true);
            TrainingPlanDtos.CreateRequest plan=json.readValue(cleanJson(completion.content()),TrainingPlanDtos.CreateRequest.class);
            return result(plan,completion);
        } catch (Exception failure) { return LlmModels.Result.rule(fallback,prompts.version()); }
    }
    public LlmModels.Result<String> answer(UUID executionId,String message,Object context,String fallback) {
        if (!properties.isEnabled()) return LlmModels.Result.rule(fallback,prompts.version());
        try {
            String prompt="USER_REQUEST:\n"+message+"\nSERVER_TOOL_CONTEXT:\n"+boundedJson(context);
            LlmModels.Completion completion=client.complete(executionId,LlmModels.Task.TRAINING_ANALYSIS,prompt,false);
            return result(completion.content(),completion);
        } catch (RuntimeException failure) { return LlmModels.Result.rule(fallback,prompts.version()); }
    }
    public Map<String,Object> status(){return client.status();}

    private AgentPlanner.Decision validate(LlmModels.AgentDecision raw) {
        if (raw==null||raw.intent()==null||raw.intent().isBlank()||raw.toolCalls()==null||raw.toolCalls().isEmpty())
            throw new IllegalArgumentException("invalid agent decision");
        List<String> names=new ArrayList<>();
        for(LlmModels.ToolCall call:raw.toolCalls()) {
            if(call==null||call.name()==null) throw new IllegalArgumentException("missing tool name");
            if(!READ_TOOLS.contains(call.name())&&!"create_training_plan".equals(call.name())) throw new IllegalArgumentException("tool not allowed");
            if(containsUserId(call.arguments())) throw new IllegalArgumentException("identity arguments are forbidden");
            if(!names.contains(call.name())) names.add(call.name());
        }
        if(names.contains("create_training_plan")&&!names.equals(PLAN_TOOLS)) throw new IllegalArgumentException("write workflow is incomplete");
        return new AgentPlanner.Decision(raw.intent().trim().toUpperCase(Locale.ROOT),List.copyOf(names));
    }
    private boolean containsUserId(Object value) {
        if(value instanceof Map<?,?> map) return map.entrySet().stream().anyMatch(entry -> "userid".equalsIgnoreCase(String.valueOf(entry.getKey()))||containsUserId(entry.getValue()));
        if(value instanceof Collection<?> collection) return collection.stream().anyMatch(this::containsUserId);
        return false;
    }
    private String boundedJson(Object value) {
        try { String serialized=json.writeValueAsString(value); return serialized.length()<=properties.getMaxContextChars()?serialized:serialized.substring(0,properties.getMaxContextChars()); }
        catch(Exception e){throw new IllegalArgumentException("cannot serialize LLM context",e);}
    }
    private String cleanJson(String value) {
        String text=value.trim(); if(text.startsWith("```")){int firstNewline=text.indexOf('\n');int last=text.lastIndexOf("```");if(firstNewline>=0&&last>firstNewline)text=text.substring(firstNewline+1,last).trim();}return text;
    }
    private <T> LlmModels.Result<T> result(T value,LlmModels.Completion completion){return new LlmModels.Result<>(value,completion.model(),completion.degraded(),prompts.version(),completion.inputTokens(),completion.outputTokens(),completion.costUsd());}
}

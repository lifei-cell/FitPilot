package com.fitpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.config.AgentProperties;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.agent.memory.AgentSessionStore;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import com.fitpilot.llm.application.LlmGateway;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.rag.dto.RagDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AgentWorkflowService {
    private final AgentPlanner planner; private final AgentToolExecutor tools; private final AgentRepository repository;
    private final AgentSessionStore sessions; private final TrainingPlanGuardrail guardrail;
    private final TrainingPlanService plans; private final AgentProperties properties; private final ObjectMapper json;
    private final LlmGateway llm;
    private final SecureRandom random = new SecureRandom();

    public AgentWorkflowService(AgentPlanner planner, AgentToolExecutor tools, AgentRepository repository,
                                AgentSessionStore sessions, TrainingPlanGuardrail guardrail,
                                TrainingPlanService plans, AgentProperties properties, ObjectMapper json,
                                LlmGateway llm) {
        this.planner=planner; this.tools=tools; this.repository=repository; this.sessions=sessions;
        this.guardrail=guardrail; this.plans=plans; this.properties=properties; this.json=json; this.llm=llm;
    }
    public AgentDtos.SessionView createSession(long userId) {
        UUID id=UUID.randomUUID(); LocalDateTime now=LocalDateTime.now(); repository.createSession(id,userId,now);
        return new AgentDtos.SessionView(id, now);
    }
    public List<AgentSessionStore.Message> messages(long userId, UUID sessionId) { owned(userId,sessionId); return sessions.messages(sessionId); }

    public AgentDtos.MessageView message(long userId, UUID sessionId, AgentDtos.MessageRequest request) {
        owned(userId, sessionId); long started=System.nanoTime();
        AgentPlanner.Decision fallbackDecision=planner.decide(request.message()); UUID executionId=UUID.randomUUID();
        repository.startExecution(executionId,userId,sessionId,fallbackDecision.intent(),fallbackDecision.tools(),LocalDateTime.now());
        LlmModels.Result<AgentPlanner.Decision> decisionResult=llm.decide(executionId,request.message(),fallbackDecision);
        AgentPlanner.Decision decision=decisionResult.value(); repository.updateDecision(executionId,decision.intent(),decision.tools());
        apply(executionId,decisionResult);
        sessions.append(sessionId,"user",request.message());
        Map<String,Object> results=new LinkedHashMap<>();
        try {
            for (String tool : decision.tools()) {
                if ("create_training_plan".equals(tool)) continue;
                long toolStart=System.nanoTime();
                try {
                    Object result=tools.execute(tool,userId,request.message()); results.put(tool,result);
                    repository.toolCall(executionId,tool,Map.of("query",request.message()),result,"SUCCEEDED",elapsed(toolStart));
                } catch (RuntimeException failure) {
                    Map<String,Object> unavailable=Map.of("available",false,"reason",safeMessage(failure)); results.put(tool,unavailable);
                    repository.toolCall(executionId,tool,Map.of("query",request.message()),unavailable,"FAILED",elapsed(toolStart));
                }
            }
            AgentDtos.PendingActionView pending=null; int violations=0;
            String model=decisionResult.model(); boolean degraded=decisionResult.degraded(); String promptVersion=decisionResult.promptVersion();
            if (decision.tools().contains("create_training_plan")) {
                TrainingPlanDtos.CreateRequest fallbackPlan=defaultProposal(userId);
                LlmModels.Result<TrainingPlanDtos.CreateRequest> planResult=request.proposedPlan()!=null
                        ? new LlmModels.Result<>(request.proposedPlan(),model,degraded,promptVersion,0,0,BigDecimal.ZERO)
                        : llm.generatePlan(executionId,request.message(),results,fallbackPlan);
                apply(executionId,planResult); model=planResult.model(); degraded|=planResult.degraded(); promptVersion=planResult.promptVersion();
                TrainingPlanDtos.CreateRequest proposal=planResult.value();
                List<String> issues=guardrail.validate(proposal); violations=issues.size();
                if (!issues.isEmpty()) {
                    repository.toolCall(executionId,"create_training_plan",proposal,Map.of("violations",issues),"REJECTED",0);
                    repository.finishExecution(executionId,"REJECTED",elapsed(started),violations);
                    String answer="计划草案未通过安全规则："+String.join("；",issues);
                    sessions.append(sessionId,"assistant",answer);
                    return new AgentDtos.MessageView(executionId,decision.intent(),decision.tools(),answer,false,null,
                            model,degraded,promptVersion,citations(results));
                }
                pending=createPending(executionId,userId,proposal);
            }
            String fallbackAnswer=compose(decision.intent(),results,pending!=null);
            LlmModels.Result<String> answerResult=pending==null?llm.answer(executionId,request.message(),results,fallbackAnswer)
                    :new LlmModels.Result<>(fallbackAnswer,model,degraded,promptVersion,0,0,BigDecimal.ZERO);
            apply(executionId,answerResult); model=answerResult.model(); degraded|=answerResult.degraded(); promptVersion=answerResult.promptVersion();
            String answer=answerResult.value();
            sessions.append(sessionId,"assistant",answer); repository.touchSession(sessionId);
            repository.finishExecution(executionId,pending==null?"SUCCEEDED":"AWAITING_CONFIRMATION",elapsed(started),violations);
            return new AgentDtos.MessageView(executionId,decision.intent(),decision.tools(),answer,pending!=null,pending,
                    model,degraded,promptVersion,citations(results));
        } catch (RuntimeException failure) {
            repository.finishExecution(executionId,"FAILED",elapsed(started),0); throw failure;
        }
    }
    private AgentDtos.PendingActionView createPending(UUID executionId,long userId,TrainingPlanDtos.CreateRequest proposal) {
        UUID id=UUID.randomUUID(); byte[] bytes=new byte[32]; random.nextBytes(bytes); String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime expires=LocalDateTime.now().plusSeconds(properties.getConfirmationTtlSeconds());
        repository.createPending(id,executionId,userId,"create_training_plan",proposal,sha256(token),expires,LocalDateTime.now());
        repository.toolCall(executionId,"create_training_plan",proposal,Map.of("pendingActionId",id,"confirmationRequired",true),"AWAITING_CONFIRMATION",0);
        return new AgentDtos.PendingActionView(id,"create_training_plan",token,expires,proposal,List.of());
    }
    @Transactional
    public Object confirm(long userId, UUID actionId, String token) {
        AgentRepository.Pending pending=repository.lockPending(actionId,userId).orElseThrow(() -> error(ErrorCode.AGENT_CONFIRMATION_INVALID,"confirmation not found",HttpStatus.NOT_FOUND));
        if (!"AWAITING_CONFIRMATION".equals(pending.status())) throw error(ErrorCode.AGENT_ACTION_ALREADY_PROCESSED,"action already processed",HttpStatus.CONFLICT);
        if (pending.expiresAt().isBefore(LocalDateTime.now()) || !MessageDigest.isEqual(pending.confirmationHash().getBytes(StandardCharsets.UTF_8),sha256(token).getBytes(StandardCharsets.UTF_8)))
            throw error(ErrorCode.AGENT_CONFIRMATION_INVALID,"confirmation is expired or invalid",HttpStatus.FORBIDDEN);
        TrainingPlanDtos.CreateRequest proposal=repository.read(pending.payload(),TrainingPlanDtos.CreateRequest.class);
        List<String> issues=guardrail.validate(proposal);
        if (!issues.isEmpty()) throw error(ErrorCode.AGENT_GUARDRAIL_REJECTED,String.join("; ",issues),HttpStatus.UNPROCESSABLE_ENTITY);
        long started=System.nanoTime(); Object result=plans.create(userId,proposal); repository.markExecuted(actionId);
        repository.toolCall(pending.executionId(),pending.tool(),proposal,result,"SUCCEEDED",elapsed(started)); return result;
    }
    public void savePreference(long userId, AgentDtos.PreferenceRequest request) { repository.upsertMemory(userId,request.key(),request.value()); }
    public List<AgentDtos.PreferenceView> preferences(long userId) { return repository.memories(userId); }
    public Object mcpTool(long userId, String tool, String query, TrainingPlanDtos.CreateRequest proposal) {
        AgentDtos.SessionView session=createSession(userId);
        if ("create_training_plan".equals(tool)) return message(userId,session.id(),new AgentDtos.MessageRequest("创建训练计划",proposal));
        UUID executionId=UUID.randomUUID(); long started=System.nanoTime();
        repository.startExecution(executionId,userId,session.id(),"MCP_TOOL",List.of(tool),LocalDateTime.now());
        try { Object result=tools.execute(tool,userId,query==null?"":query); repository.toolCall(executionId,tool,Map.of("query",query==null?"":query),result,"SUCCEEDED",elapsed(started)); repository.finishExecution(executionId,"SUCCEEDED",elapsed(started),0); return result; }
        catch(RuntimeException e){ repository.toolCall(executionId,tool,Map.of(),Map.of("error",safeMessage(e)),"FAILED",elapsed(started)); repository.finishExecution(executionId,"FAILED",elapsed(started),0); throw e; }
    }
    private TrainingPlanDtos.CreateRequest defaultProposal(long userId) {
        try {
            var active=plans.active(userId);
            return new TrainingPlanDtos.CreateRequest(active.name()+" - Agent 草案","基于当前计划生成，确认后保存",active.goal(),
                    Math.min(active.durationWeeks()==null?8:active.durationWeeks(),16),active.days().stream().map(day ->
                    new TrainingPlanDtos.DayRequest(day.dayNumber(),day.name(),day.notes(),day.exercises().stream().map(ex ->
                    new TrainingPlanDtos.ExerciseRequest(ex.exerciseId(),ex.sequence(),ex.targetSets(),ex.targetRepsMin(),ex.targetRepsMax(),ex.targetRpe(),ex.restSeconds(),ex.notes())).toList())).toList());
        } catch (RuntimeException ignored) {
            int frequency=preferredFrequency(userId);
            List<TrainingPlanDtos.DayRequest> days=new ArrayList<>();
            for(int day=1;day<=frequency;day++) days.add(new TrainingPlanDtos.DayRequest(day,"全身训练 "+day,"Agent 基础草案",List.of(
                    exercise(1,1),exercise(2,2),exercise(3,3))));
            return new TrainingPlanDtos.CreateRequest("Agent 基础训练计划","确认后仅保存为草稿","GENERAL_FITNESS",8,days);
        }
    }
    private int preferredFrequency(long userId) {
        return repository.memories(userId).stream().filter(memory -> "weekly_frequency".equals(memory.key()))
                .map(AgentDtos.PreferenceView::value).filter(Number.class::isInstance).map(Number.class::cast)
                .map(Number::intValue).map(value -> Math.max(2,Math.min(6,value))).findFirst().orElse(3);
    }
    private TrainingPlanDtos.ExerciseRequest exercise(long id,int sequence) { return new TrainingPlanDtos.ExerciseRequest(id,sequence,3,8,12,BigDecimal.valueOf(7),90,null); }
    private String compose(String intent,Map<String,Object> results,boolean confirmation) {
        if (confirmation) return "已结合画像、历史、PR、当前计划、训练量和知识库生成结构化计划草案。通过领域与安全规则，等待你明确确认后才会保存。";
        long available=results.values().stream().filter(v -> !(v instanceof Map<?,?> m) || !Boolean.FALSE.equals(m.get("available"))).count();
        return "已完成 "+intent+" 分析，调用 "+results.size()+" 个只读工具，其中 "+available+" 个返回有效结果。详细数据已保留在工具调用审计中。";
    }
    private void apply(UUID executionId,LlmModels.Result<?> result){repository.addLlmUsage(executionId,result.model(),result.promptVersion(),result.degraded(),result.inputTokens(),result.outputTokens(),result.costUsd());}
    private List<RagDtos.Citation> citations(Map<String,Object> results){Object value=results.get("search_knowledge");if(!(value instanceof RagDtos.SearchResponse response))return List.of();return response.contexts().stream().map(RagDtos.RetrievedContext::citation).filter(Objects::nonNull).distinct().toList();}
    private void owned(long userId,UUID sessionId) { if(!repository.ownsSession(sessionId,userId)) throw error(ErrorCode.AGENT_SESSION_NOT_FOUND,"agent session not found",HttpStatus.NOT_FOUND); }
    private BusinessException error(ErrorCode code,String message,HttpStatus status){ return new BusinessException(code,message,status); }
    private long elapsed(long started){return (System.nanoTime()-started)/1_000_000;}
    private String safeMessage(Throwable t){return t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();}
    private String sha256(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}

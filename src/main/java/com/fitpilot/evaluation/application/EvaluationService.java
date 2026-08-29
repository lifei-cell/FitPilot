package com.fitpilot.evaluation.application;

import com.fitpilot.agent.application.AgentPlanner;
import com.fitpilot.evaluation.domain.EvaluationCases;
import com.fitpilot.evaluation.dto.EvaluationDtos;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.llm.application.LlmGateway;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.dto.RagDtos;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class EvaluationService {
    private final EvaluationDatasetLoader datasets;private final EvaluationRepository repository;
    private final AgentPlanner planner;private final LlmGateway llm;private final PromptRegistry prompts;
    private final ObjectProvider<HybridRetrievalService> retrieval;private final TaskExecutor executor;
    public EvaluationService(EvaluationDatasetLoader datasets,EvaluationRepository repository,AgentPlanner planner,
                             LlmGateway llm,PromptRegistry prompts,ObjectProvider<HybridRetrievalService> retrieval,
                             @Qualifier("evaluationExecutor")TaskExecutor executor){this.datasets=datasets;this.repository=repository;this.planner=planner;this.llm=llm;this.prompts=prompts;this.retrieval=retrieval;this.executor=executor;}
    public UUID startAgent(String requestedMode){String mode=requestedMode==null||requestedMode.isBlank()?"RULE_WORKFLOW":requestedMode;UUID id=UUID.randomUUID();repository.createAgent(id,EvaluationDatasetLoader.AGENT_VERSION,mode,mode,prompts.version());executor.execute(()->runAgent(id,mode));return id;}
    public UUID startRag(){UUID id=UUID.randomUUID();repository.createRag(id,EvaluationDatasetLoader.RAG_VERSION);executor.execute(()->runRag(id));return id;}
    public Optional<EvaluationDtos.RunView> find(UUID id){return repository.find(id);}
    void runAgent(UUID runId,String mode){try{List<EvaluationCases.AgentCase> cases=datasets.agent();int passed=0,selectionCorrect=0,success=0,violations=0,hallucinations=0;String model=mode;
        for(var item:cases){long started=System.nanoTime();AgentPlanner.Decision fallback=planner.decide(item.query());LlmModels.Result<AgentPlanner.Decision> result="ACTIVE_MODEL".equals(mode)?llm.decide(null,item.query(),fallback):LlmModels.Result.rule(fallback,prompts.version());List<String> actual=result.value().tools();boolean selected=actual.equals(item.expectedTools());boolean violation=actual.stream().anyMatch(item.forbiddenTools()::contains);boolean hallucination=actual.stream().anyMatch(tool->!LlmGateway.READ_TOOLS.contains(tool)&&!"create_training_plan".equals(tool));boolean task=selected&&!violation&&!hallucination;if(selected)selectionCorrect++;if(task){success++;passed++;}if(violation)violations++;if(hallucination)hallucinations++;model=result.model();repository.agentResult(runId,item.id(),hash(item.query()),item.expectedTools(),actual,selected,task,violation,hallucination,elapsed(started));}
        int total=cases.size();repository.finishAgent(runId,total,passed,Map.of("toolSelectionAccuracy",ratio(selectionCorrect,total),"taskSuccessRate",ratio(success,total),"constraintViolationRate",ratio(violations,total),"hallucinationRate",ratio(hallucinations,total)),model);
    }catch(Exception e){repository.failAgent(runId,e.getMessage());}}
    void runRag(UUID runId){try{HybridRetrievalService service=retrieval.getIfAvailable();if(service==null)throw new IllegalStateException("RAG is disabled");List<EvaluationCases.RagCase> cases=datasets.rag();int passed=0;double recall=0,rr=0,ndcg=0,precision=0,contextRecall=0;
        for(var item:cases){long started=System.nanoTime();RagDtos.SearchResponse response=service.search(item.query(),5,item.category());List<RagDtos.RetrievedContext> contexts=response.contexts();List<Integer> relevant=new ArrayList<>();for(int i=0;i<contexts.size();i++)if(relevant(contexts.get(i),item.expectedTerms()))relevant.add(i);double caseRecall=relevant.isEmpty()?0:1;double caseRr=relevant.isEmpty()?0:1d/(relevant.getFirst()+1);double caseNdcg=relevant.stream().mapToDouble(rank->1d/log2(rank+2)).sum();caseNdcg=Math.min(1,caseNdcg);double casePrecision=contexts.isEmpty()?0:(double)relevant.size()/contexts.size();if(caseRecall==1)passed++;recall+=caseRecall;rr+=caseRr;ndcg+=caseNdcg;precision+=casePrecision;contextRecall+=caseRecall;repository.ragResult(runId,item.id(),hash(item.query()),caseRecall,caseRr,caseNdcg,casePrecision,caseRecall,elapsed(started));}
        int total=cases.size();repository.finishRag(runId,total,passed,Map.of("recallAt5",ratio(recall,total),"mrr",ratio(rr,total),"ndcg",ratio(ndcg,total),"contextPrecision",ratio(precision,total),"contextRecall",ratio(contextRecall,total)));
    }catch(Exception e){repository.failRag(runId,e.getMessage());}}
    private boolean relevant(RagDtos.RetrievedContext context,List<String> terms){String text=(context.title()+" "+context.heading()+" "+context.content()).toLowerCase(Locale.ROOT);return terms.stream().anyMatch(term->text.contains(term.toLowerCase(Locale.ROOT)));}
    private double log2(double value){return Math.log(value)/Math.log(2);}
    private double ratio(double value,int total){return total==0?0:Math.round(value/total*10000d)/10000d;}
    private long elapsed(long started){return(System.nanoTime()-started)/1_000_000;}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}

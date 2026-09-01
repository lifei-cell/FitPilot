package com.fitpilot.evaluation.application;

import com.fitpilot.agent.application.AgentPlanner;
import com.fitpilot.evaluation.domain.EvaluationCases;
import com.fitpilot.evaluation.dto.EvaluationDtos;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.llm.application.LlmGateway;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.rag.application.KnowledgeIngestionService;
import com.fitpilot.rag.dto.RagDtos;
import com.fitpilot.rag.infrastructure.RagGovernanceRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class EvaluationService {
    private final EvaluationDatasetLoader datasets;
    private final EvaluationRepository repository;
    private final AgentPlanner planner;
    private final LlmGateway llm;
    private final PromptRegistry prompts;
    private final ObjectProvider<HybridRetrievalService> retrieval;
    private final ObjectProvider<KnowledgeIngestionService> ingestion;
    private final TaskExecutor executor;
    private final RagGovernanceRepository governance;

    public EvaluationService(EvaluationDatasetLoader datasets, EvaluationRepository repository, AgentPlanner planner,
                             LlmGateway llm, PromptRegistry prompts,
                             ObjectProvider<HybridRetrievalService> retrieval,
                             ObjectProvider<KnowledgeIngestionService> ingestion,
                             @Qualifier("evaluationExecutor") TaskExecutor executor,
                             RagGovernanceRepository governance) {
        this.datasets = datasets;
        this.repository = repository;
        this.planner = planner;
        this.llm = llm;
        this.prompts = prompts;
        this.retrieval = retrieval;
        this.ingestion = ingestion;
        this.executor = executor;
        this.governance = governance;
    }
    public UUID startAgent(String requestedMode){String mode=requestedMode==null||requestedMode.isBlank()?"RULE_WORKFLOW":requestedMode;UUID id=UUID.randomUUID();repository.createAgent(id,EvaluationDatasetLoader.AGENT_VERSION,mode,mode,prompts.version());executor.execute(()->runAgent(id,mode));return id;}
    public UUID startRag(){
        UUID id=UUID.randomUUID();
        List<RagGovernanceRepository.DynamicEvalCase> dynamic=List.copyOf(governance.dynamicCases());
        List<EvaluationCases.RagCase> frozen=new ArrayList<>(datasets.rag());
        dynamic.forEach(item->frozen.add(new EvaluationCases.RagCase("dynamic-"+item.id(),item.query(),List.of(),item.expectedSources(),item.category())));
        long version=dynamic.stream().mapToLong(RagGovernanceRepository.DynamicEvalCase::version).max().orElse(0);
        String dataset=EvaluationDatasetLoader.RAG_VERSION+"+dynamic-"+version;
        repository.createRag(id,dataset,Map.of("staticVersion",EvaluationDatasetLoader.RAG_VERSION,
                "dynamicVersion",version,"staticCases",datasets.rag().size(),"dynamicCases",dynamic.size()));
        executor.execute(()->runRag(id,List.copyOf(frozen)));return id;
    }
    public Optional<EvaluationDtos.RunView> find(UUID id){return repository.find(id);}
    void runAgent(UUID runId,String mode){try{List<EvaluationCases.AgentCase> cases=datasets.agent();int passed=0,selectionCorrect=0,success=0,violations=0,hallucinations=0;String model=mode;
        for(var item:cases){long started=System.nanoTime();AgentPlanner.Decision fallback=planner.decide(item.query());LlmModels.Result<AgentPlanner.Decision> result="ACTIVE_MODEL".equals(mode)?llm.decide(null,item.query(),fallback):LlmModels.Result.rule(fallback,prompts.version());List<String> actual=result.value().tools();boolean selected=actual.equals(item.expectedTools());boolean violation=actual.stream().anyMatch(item.forbiddenTools()::contains);boolean hallucination=actual.stream().anyMatch(tool->!LlmGateway.READ_TOOLS.contains(tool)&&!"create_training_plan".equals(tool));boolean task=selected&&!violation&&!hallucination;if(selected)selectionCorrect++;if(task){success++;passed++;}if(violation)violations++;if(hallucination)hallucinations++;model=result.model();repository.agentResult(runId,item.id(),hash(item.query()),item.expectedTools(),actual,selected,task,violation,hallucination,elapsed(started));}
        int total=cases.size();repository.finishAgent(runId,total,passed,Map.of("toolSelectionAccuracy",ratio(selectionCorrect,total),"taskSuccessRate",ratio(success,total),"constraintViolationRate",ratio(violations,total),"hallucinationRate",ratio(hallucinations,total)),model);
    }catch(Exception e){repository.failAgent(runId,e.getMessage());}}
    void runRag(UUID runId, List<EvaluationCases.RagCase> cases) {
        KnowledgeIngestionService loader = ingestion.getIfAvailable();
        List<UUID> evaluationDocuments = new ArrayList<>();
        boolean cleaned = false;
        try {
            HybridRetrievalService service = retrieval.getIfAvailable();
            if (service == null || loader == null) throw new IllegalStateException("RAG is disabled");
            for (var document : datasets.ragCorpus()) {
                evaluationDocuments.add(loader.ingest(scoped(document, runId)).id());
            }
            int passed = 0;
            double recall = 0, rr = 0, ndcg = 0, precision = 0, contextRecall = 0, citationValidity = 0;
            Map<String, CategoryStats> categories = new LinkedHashMap<>();
            for (var item : cases) {
                long started = System.nanoTime();
                RagDtos.SearchResponse response = service.search(item.query(), 5, item.category());
                List<RagDtos.RetrievedContext> contexts = response.contexts();
                Set<String> expected = new LinkedHashSet<>(item.expectedSourceUrls());
                List<String> actualSources = contexts.stream().map(RagDtos.RetrievedContext::citation)
                        .filter(Objects::nonNull).map(RagDtos.Citation::sourceUrl).toList();
                List<Integer> relevant = new ArrayList<>();
                for (int i = 0; i < contexts.size(); i++) {
                    if (expected.contains(contexts.get(i).citation().sourceUrl())) relevant.add(i);
                }
                double caseRecall = relevant.isEmpty() ? 0 : 1;
                double caseRr = relevant.isEmpty() ? 0 : 1d / (relevant.getFirst() + 1);
                double caseNdcg = Math.min(1, relevant.stream().mapToDouble(rank -> 1d / log2(rank + 2)).sum());
                double casePrecision = contexts.isEmpty() ? 0 : (double) relevant.size() / contexts.size();
                double caseContextRecall = expected.isEmpty() ? 0
                        : (double) actualSources.stream().filter(expected::contains).distinct().count() / expected.size();
                boolean citationValid = contexts.stream().allMatch(this::validCitation);
                if (caseRecall == 1 && citationValid) passed++;
                recall += caseRecall;
                rr += caseRr;
                ndcg += caseNdcg;
                precision += casePrecision;
                contextRecall += caseContextRecall;
                if (citationValid) citationValidity++;
                categories.computeIfAbsent(metricCategory(item.category()), ignored -> new CategoryStats())
                        .add(caseRecall, caseRr);
                repository.ragResult(runId, item.id(), hash(item.query()), item.expectedSourceUrls(), actualSources,
                        caseRecall, caseRr, caseNdcg, casePrecision, caseContextRecall, citationValid, elapsed(started));
            }
            int total = cases.size();
            cleanupDocuments(loader, evaluationDocuments);
            cleaned = true;
            Map<String,Double> resultMetrics=new LinkedHashMap<>();
            resultMetrics.put("recallAt5",ratio(recall,total));resultMetrics.put("mrr",ratio(rr,total));
            resultMetrics.put("ndcg",ratio(ndcg,total));resultMetrics.put("contextPrecision",ratio(precision,total));
            resultMetrics.put("contextRecall",ratio(contextRecall,total));
            resultMetrics.put("citationValidity",ratio(citationValidity,total));
            categories.forEach((category,stats)->{resultMetrics.put("category."+category+".recallAt5",ratio(stats.recall,stats.total));
                resultMetrics.put("category."+category+".mrr",ratio(stats.rr,stats.total));});
            Map<String,Double> baseline=repository.lastSuccessfulRagMetrics(runId);
            List<String> failures=new ArrayList<>();
            if(resultMetrics.get("citationValidity")<1)failures.add("Citation Validity must be 100%");
            resultMetrics.forEach((key,value)->{if(key.startsWith("category.")&&(key.endsWith(".recallAt5")||key.endsWith(".mrr"))
                    &&baseline.containsKey(key)&&baseline.get(key)-value>.05)failures.add(key+" regressed more than 5pp");});
            if(failures.isEmpty())repository.finishRag(runId,total,passed,resultMetrics);
            else repository.failRagGate(runId,total,passed,resultMetrics,String.join("; ",failures));
        } catch (Exception failure) {
            repository.failRag(runId, failure.getMessage());
        } finally {
            if (!cleaned && loader != null) cleanupDocuments(loader, evaluationDocuments);
        }
    }

    private RagDtos.IngestDocumentRequest scoped(RagDtos.IngestDocumentRequest source, UUID runId) {
        Map<String, String> metadata = new LinkedHashMap<>(source.metadata() == null ? Map.of() : source.metadata());
        metadata.put("evaluationRunId", runId.toString());
        return new RagDtos.IngestDocumentRequest(source.externalId() + "-" + runId, source.title(), source.category(),
                source.sourceUrl(), source.sourceLicense(), source.format(), source.content(), metadata,
                source.publisher(), source.trustLevel(), source.effectiveFrom(), source.expiresAt());
    }

    private void deleteQuietly(KnowledgeIngestionService loader, UUID documentId) {
        try { loader.delete(documentId); }
        catch (RuntimeException ignored) { }
    }

    private void cleanupDocuments(KnowledgeIngestionService loader, List<UUID> documentIds) {
        documentIds.forEach(id -> deleteQuietly(loader, id));
    }
    private boolean validCitation(RagDtos.RetrievedContext context){RagDtos.Citation citation=context.citation();return citation!=null&&citation.sourceUrl()!=null&&!citation.sourceUrl().isBlank()&&citation.sourceLicense()!=null&&!citation.sourceLicense().isBlank()
            &&citation.documentVersion()>0&&Set.of("OFFICIAL","INTERNAL","PROFESSIONAL","COMMUNITY").contains(citation.trustLevel())
            &&(citation.expiresAt()==null||citation.expiresAt().isAfter(java.time.LocalDateTime.now()));}
    private double log2(double value){return Math.log(value)/Math.log(2);}
    private double ratio(double value,int total){return total==0?0:Math.round(value/total*10000d)/10000d;}
    private long elapsed(long started){return(System.nanoTime()-started)/1_000_000;}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String metricCategory(String value){return(value==null||value.isBlank()?"uncategorized":value).replaceAll("[^a-zA-Z0-9_-]","_");}
    private static final class CategoryStats{double recall;double rr;int total;void add(double recall,double rr){this.recall+=recall;this.rr+=rr;total++;}}
}

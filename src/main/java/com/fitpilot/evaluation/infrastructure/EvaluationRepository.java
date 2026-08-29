package com.fitpilot.evaluation.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.evaluation.dto.EvaluationDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;

@Repository
public class EvaluationRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public EvaluationRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public void createAgent(UUID id,String dataset,String mode,String model,String prompt){jdbc.update("INSERT INTO agent_eval_run(id,dataset_version,mode,model,prompt_version,status,started_at) VALUES (?,?,?,?,?,'RUNNING',now())",id,dataset,mode,model,prompt);}
    public void createRag(UUID id,String dataset){jdbc.update("INSERT INTO rag_eval_run(id,dataset_version,status,started_at) VALUES (?,?,'RUNNING',now())",id,dataset);}
    public void agentResult(UUID run,String caseId,String hash,List<String> expected,List<String> actual,boolean selection,boolean success,boolean violation,boolean hallucination,long latency){jdbc.update("INSERT INTO agent_eval_result(run_id,case_id,query_hash,expected_tools,actual_tools,tool_selection_correct,task_success,constraint_violation,hallucination,latency_ms) VALUES (?,?,?,?::jsonb,?::jsonb,?,?,?,?,?)",run,caseId,hash,write(expected),write(actual),selection,success,violation,hallucination,latency);}
    public void ragResult(UUID run,String caseId,String hash,double recall,double rr,double ndcg,double precision,double contextRecall,long latency){jdbc.update("INSERT INTO rag_eval_result(run_id,case_id,query_hash,recall_at_5,reciprocal_rank,ndcg,context_precision,context_recall,latency_ms) VALUES (?,?,?,?,?,?,?,?,?)",run,caseId,hash,recall,rr,ndcg,precision,contextRecall,latency);}
    public void finishAgent(UUID id,int total,int passed,Map<String,Double> metrics,String model){jdbc.update("UPDATE agent_eval_run SET status='SUCCEEDED',total_cases=?,passed_cases=?,metrics=?::jsonb,model=?,completed_at=now() WHERE id=?",total,passed,write(metrics),model,id);}
    public void finishRag(UUID id,int total,int passed,Map<String,Double> metrics){jdbc.update("UPDATE rag_eval_run SET status='SUCCEEDED',total_cases=?,passed_cases=?,metrics=?::jsonb,completed_at=now() WHERE id=?",total,passed,write(metrics),id);}
    public void failAgent(UUID id,String message){jdbc.update("UPDATE agent_eval_run SET status='FAILED',error_message=?,completed_at=now() WHERE id=?",safe(message),id);}
    public void failRag(UUID id,String message){jdbc.update("UPDATE rag_eval_run SET status='FAILED',error_message=?,completed_at=now() WHERE id=?",safe(message),id);}
    public Optional<EvaluationDtos.RunView> find(UUID id){Optional<EvaluationDtos.RunView> agent=jdbc.query("SELECT id,dataset_version,mode,model,prompt_version,status,total_cases,passed_cases,metrics::text,error_message,started_at,completed_at FROM agent_eval_run WHERE id=?",rs->rs.next()?Optional.of(view(rs,"AGENT")):Optional.empty(),id);if(agent.isPresent())return agent;return jdbc.query("SELECT id,dataset_version,NULL,NULL,NULL,status,total_cases,passed_cases,metrics::text,error_message,started_at,completed_at FROM rag_eval_run WHERE id=?",rs->rs.next()?Optional.of(view(rs,"RAG")):Optional.empty(),id);}
    private EvaluationDtos.RunView view(java.sql.ResultSet rs,String type)throws java.sql.SQLException{return new EvaluationDtos.RunView((UUID)rs.getObject(1),type,rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getInt(8),readMetrics(rs.getString(9)),rs.getString(10),rs.getTimestamp(11).toLocalDateTime(),rs.getTimestamp(12)==null?null:rs.getTimestamp(12).toLocalDateTime());}
    private Map<String,Double> readMetrics(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
    private String safe(String value){if(value==null)return "evaluation failed";return value.length()>500?value.substring(0,500):value;}
}

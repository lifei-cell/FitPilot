package com.fitpilot.evaluation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.evaluation.domain.EvaluationCases;
import com.fitpilot.rag.dto.RagDtos;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class EvaluationDatasetLoader {
    public static final String AGENT_VERSION="agent-v1";public static final String RAG_VERSION="rag-v1.1";
    private final ObjectMapper json;
    public EvaluationDatasetLoader(ObjectMapper json){this.json=json;}
    public List<EvaluationCases.AgentCase> agent(){return read("eval/agent-v1.jsonl",EvaluationCases.AgentCase.class);}
    public List<EvaluationCases.RagCase> rag(){return read("eval/rag-v1.jsonl",EvaluationCases.RagCase.class);}
    public List<RagDtos.IngestDocumentRequest> ragCorpus(){return read("eval/rag-corpus-v1.jsonl",RagDtos.IngestDocumentRequest.class);}
    private <T> List<T> read(String path,Class<T> type){try(var reader=new BufferedReader(new InputStreamReader(new ClassPathResource(path).getInputStream(),StandardCharsets.UTF_8))){List<T> values=new ArrayList<>();String line;while((line=reader.readLine())!=null){if(!line.isBlank())values.add(json.readValue(line,type));}return List.copyOf(values);}catch(Exception e){throw new IllegalStateException("cannot load evaluation dataset "+path,e);}}
}

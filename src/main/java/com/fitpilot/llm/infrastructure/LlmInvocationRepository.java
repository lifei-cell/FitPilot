package com.fitpilot.llm.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import com.fitpilot.llm.dto.LlmDtos;

@Repository
public class LlmInvocationRepository {
    private final JdbcTemplate jdbc;
    public LlmInvocationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void record(UUID executionId, String provider, String model, String task, String promptVersion,
                       String status, int inputTokens, int outputTokens, BigDecimal cost, long latency,
                       Integer httpStatus, String errorCode) {
        jdbc.update("""
                INSERT INTO llm_invocation(id,execution_id,provider,model,task_type,prompt_version,status,
                  input_tokens,output_tokens,cost_usd,latency_ms,http_status,error_code,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), executionId, provider, model, task, promptVersion, status,
                inputTokens, outputTokens, cost, latency, httpStatus, errorCode, LocalDateTime.now());
    }
    public List<LlmDtos.InvocationView> list(String status,String model,int limit){
        String sql="""
                SELECT id,execution_id,provider,model,task_type,prompt_version,status,input_tokens,output_tokens,
                  cost_usd,latency_ms,http_status,error_code,created_at FROM llm_invocation
                WHERE (CAST(? AS VARCHAR) IS NULL OR status=?)
                  AND (CAST(? AS VARCHAR) IS NULL OR model=?) ORDER BY created_at DESC LIMIT ?
                """;
        return jdbc.query(sql,(rs,n)->new LlmDtos.InvocationView((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8),rs.getInt(9),rs.getBigDecimal(10),rs.getLong(11),(Integer)rs.getObject(12),rs.getString(13),rs.getTimestamp(14).toLocalDateTime()),status,status,model,model,Math.max(1,Math.min(100,limit)));
    }
    public int deleteOlderThan(LocalDateTime cutoff){return jdbc.update("DELETE FROM llm_invocation WHERE created_at<?",cutoff);}
}

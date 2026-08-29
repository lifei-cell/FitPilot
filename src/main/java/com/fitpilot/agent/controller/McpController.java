package com.fitpilot.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.application.AgentWorkflowService;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.common.security.CurrentUser;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/mcp")
public class McpController {
    private static final String VERSION="2026-07-28";
    private static final List<String> READ_TOOLS=List.of("get_user_profile","get_workout_history","get_personal_records","get_training_plan","get_training_volume","search_knowledge");
    private final AgentWorkflowService agent; private final ObjectMapper json;
    public McpController(AgentWorkflowService agent,ObjectMapper json){this.agent=agent;this.json=json;}
    @PostMapping
    ResponseEntity<Map<String,Object>> call(@RequestHeader("MCP-Protocol-Version") String version,
                                            @RequestHeader("Mcp-Method") String method,
                                            @RequestHeader(value="Mcp-Name",required=false) String name,
                                            @RequestBody AgentDtos.McpRequest request, Authentication auth){
        if(!VERSION.equals(version)||!"2.0".equals(request.jsonrpc())||!method.equals(request.method()))return ResponseEntity.badRequest().body(error(request.id(),-32600,"invalid MCP version or method headers"));
        try{return ResponseEntity.ok(success(request.id(),dispatch(CurrentUser.id(auth),method,name,request.params())));}
        catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(error(request.id(),-32602,e.getMessage()));}
    }
    private Object dispatch(long userId,String method,String name,Map<String,Object> params){
        return switch(method){
            case "server/discover" -> Map.of("protocolVersion",VERSION,"capabilities",Map.of("tools",Map.of(),"resources",Map.of()),"serverInfo",Map.of("name","fitpilot-agent","version","4.0.0"));
            case "tools/list" -> Map.of("tools",toolDefinitions());
            case "tools/call" -> callTool(userId,requiredName(name,params),params==null?Map.of():params);
            case "resources/list" -> Map.of("resources",List.of(resource("fitness://user/profile","用户画像"),resource("fitness://user/history","训练历史"),resource("fitness://user/plan","当前计划")));
            case "resources/read" -> readResource(userId,String.valueOf(params.get("uri")));
            default -> throw new IllegalArgumentException("unsupported MCP method");
        };
    }
    private Object callTool(long userId,String tool,Map<String,Object> params){
        if(!READ_TOOLS.contains(tool)&&!"create_training_plan".equals(tool))throw new IllegalArgumentException("unknown tool");
        TrainingPlanDtos.CreateRequest proposal=null;
        if("create_training_plan".equals(tool)&&params.get("plan")!=null) proposal=json.convertValue(params.get("plan"),TrainingPlanDtos.CreateRequest.class);
        Object result=agent.mcpTool(userId,tool,String.valueOf(params.getOrDefault("query","")),proposal);
        if(result instanceof AgentDtos.MessageView message&&message.confirmationRequired())
            return Map.of("resultType","input_required","content",List.of(Map.of("type","text","text",stringify(result))));
        return Map.of("resultType","result","content",List.of(Map.of("type","text","text",stringify(result))));
    }
    private Object readResource(long userId,String uri){String tool=switch(uri){case "fitness://user/profile"->"get_user_profile";case "fitness://user/history"->"get_workout_history";case "fitness://user/plan"->"get_training_plan";default->throw new IllegalArgumentException("unknown resource");};return Map.of("contents",List.of(Map.of("uri",uri,"mimeType","application/json","text",stringify(agent.mcpTool(userId,tool,"",null)))));}
    private List<Map<String,Object>> toolDefinitions(){List<Map<String,Object>> list=new ArrayList<>();for(String tool:READ_TOOLS)list.add(Map.of("name",tool,"description","FitPilot owner-scoped read tool","inputSchema",Map.of("type","object","properties",Map.of("query",Map.of("type","string")))));list.add(Map.of("name","create_training_plan","description","Validate and propose a plan; explicit confirmation is required before persistence","inputSchema",Map.of("type","object","properties",Map.of("plan",Map.of("type","object")))));return list;}
    private Map<String,Object> resource(String uri,String name){return Map.of("uri",uri,"name",name,"mimeType","application/json");}
    private String requiredName(String header,Map<String,Object> params){String candidate=header!=null?header:params==null?null:String.valueOf(params.get("name"));if(candidate==null||candidate.isBlank())throw new IllegalArgumentException("Mcp-Name is required");return candidate;}
    private Map<String,Object> success(Object id,Object result){return map("jsonrpc","2.0","id",id,"result",result);}
    private Map<String,Object> error(Object id,int code,String message){return map("jsonrpc","2.0","id",id,"error",Map.of("code",code,"message",message));}
    private Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
    private String stringify(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("cannot serialize MCP result");}}
}

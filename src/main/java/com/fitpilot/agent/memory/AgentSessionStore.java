package com.fitpilot.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.config.AgentProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class AgentSessionStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AgentProperties properties;
    public AgentSessionStore(StringRedisTemplate redis, ObjectMapper json, AgentProperties properties) {
        this.redis = redis; this.json = json; this.properties = properties;
    }
    public void append(UUID sessionId, String role, String content) {
        String key = "agent:session:" + sessionId;
        try {
            redis.opsForList().rightPush(key, json.writeValueAsString(new Message(role, content, LocalDateTime.now())));
            redis.opsForList().trim(key, -properties.getMaxMessages(), -1);
            redis.expire(key, Duration.ofSeconds(properties.getSessionTtlSeconds()));
        } catch (Exception e) { throw new IllegalStateException("agent short-term memory unavailable", e); }
    }
    public List<Message> messages(UUID sessionId) {
        List<String> values = redis.opsForList().range("agent:session:" + sessionId, 0, -1);
        if (values == null) return List.of();
        return values.stream().map(value -> { try { return json.readValue(value, Message.class); }
            catch (Exception e) { throw new IllegalStateException(e); }}).toList();
    }
    public record Message(String role, String content, LocalDateTime createdAt) {}
}

package com.fitpilot.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.config.AgentProperties;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Observed(name = "fitpilot.redis.agent-session")
public class AgentSessionStore {
    private static final Logger log = LoggerFactory.getLogger(AgentSessionStore.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AgentProperties properties;
    private final AgentRepository repository;

    public AgentSessionStore(StringRedisTemplate redis, ObjectMapper json, AgentProperties properties,
                             AgentRepository repository) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
        this.repository = repository;
    }

    public AgentDtos.ConversationMessage append(UUID sessionId, String role, String content) {
        return append(sessionId, role, content, "COMPLETED", null, Map.of());
    }

    public AgentDtos.ConversationMessage append(UUID sessionId, String role, String content, String status,
                                                 UUID executionId, Map<String, Object> metadata) {
        AgentDtos.ConversationMessage stored = repository.appendMessage(sessionId, role, content, status,
                executionId, metadata);
        cache(stored, sessionId);
        return stored;
    }

    public List<Message> messages(UUID sessionId) {
        return recent(sessionId).stream()
                .map(item -> new Message(item.role(), item.content(),
                        LocalDateTime.ofInstant(item.createdAt(), java.time.ZoneOffset.UTC)))
                .toList();
    }

    public AgentDtos.MessagePage history(UUID sessionId, Long beforeId, int limit) {
        return repository.messageHistory(sessionId, beforeId, limit);
    }

    private List<AgentDtos.ConversationMessage> recent(UUID sessionId) {
        List<String> values = cachedValues(sessionId);
        if (!values.isEmpty()) {
            List<AgentDtos.ConversationMessage> parsed = parseCurrent(values);
            if (!parsed.isEmpty()) return parsed;
            migrateLegacy(sessionId, values);
        }
        List<AgentDtos.ConversationMessage> stored = repository.recentMessages(sessionId, properties.getMaxMessages());
        replaceCache(sessionId, stored);
        return stored;
    }

    private List<String> cachedValues(UUID sessionId) {
        try {
            List<String> values = redis.opsForList().range(key(sessionId), 0, -1);
            return values == null ? List.of() : values;
        } catch (RuntimeException failure) {
            log.warn("Agent message cache unavailable, using PostgreSQL sessionId={}: {}", sessionId,
                    failure.getMessage());
            return List.of();
        }
    }

    private List<AgentDtos.ConversationMessage> parseCurrent(List<String> values) {
        List<AgentDtos.ConversationMessage> result = new ArrayList<>();
        try {
            for (String value : values) {
                JsonNode node = json.readTree(value);
                if (!node.hasNonNull("id")) return List.of();
                result.add(json.treeToValue(node, AgentDtos.ConversationMessage.class));
            }
            return result;
        } catch (Exception failure) {
            log.warn("Ignoring malformed Agent cache entry: {}", failure.getMessage());
            return List.of();
        }
    }

    private void migrateLegacy(UUID sessionId, List<String> values) {
        if (repository.messageCount(sessionId) > 0) return;
        try {
            for (String value : values) {
                LegacyMessage message = json.readValue(value, LegacyMessage.class);
                repository.appendMessage(sessionId, message.role(), message.content(), "COMPLETED", null,
                        Map.of("migratedFrom", "redis"));
            }
        } catch (Exception failure) {
            log.warn("Unable to migrate legacy Agent cache sessionId={}: {}", sessionId, failure.getMessage());
        }
    }

    private void cache(AgentDtos.ConversationMessage message, UUID sessionId) {
        try {
            redis.opsForList().rightPush(key(sessionId), json.writeValueAsString(message));
            redis.opsForList().trim(key(sessionId), -properties.getMaxMessages(), -1);
            redis.expire(key(sessionId), Duration.ofSeconds(properties.getSessionTtlSeconds()));
        } catch (Exception failure) {
            log.warn("Agent message persisted but cache update failed sessionId={}: {}", sessionId,
                    failure.getMessage());
        }
    }

    private void replaceCache(UUID sessionId, List<AgentDtos.ConversationMessage> messages) {
        if (messages.isEmpty()) return;
        try {
            redis.delete(key(sessionId));
            for (AgentDtos.ConversationMessage message : messages) {
                redis.opsForList().rightPush(key(sessionId), json.writeValueAsString(message));
            }
            redis.expire(key(sessionId), Duration.ofSeconds(properties.getSessionTtlSeconds()));
        } catch (Exception failure) {
            log.warn("Unable to warm Agent message cache sessionId={}: {}", sessionId, failure.getMessage());
        }
    }

    private String key(UUID sessionId) {
        return "agent:session:" + sessionId;
    }

    public record Message(String role, String content, LocalDateTime createdAt) {}
    private record LegacyMessage(String role, String content, LocalDateTime createdAt) {}
}

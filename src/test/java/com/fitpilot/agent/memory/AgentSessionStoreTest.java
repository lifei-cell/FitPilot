package com.fitpilot.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.agent.config.AgentProperties;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentSessionStoreTest {
    @Test
    void persistsBeforeBestEffortCacheWrite() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(lists.rightPush(anyString(), anyString())).thenThrow(new IllegalStateException("redis down"));
        AgentRepository repository = mock(AgentRepository.class);
        UUID sessionId = UUID.randomUUID();
        var stored = new AgentDtos.ConversationMessage(1, "user", "hello", "COMPLETED", null, Map.of(), Instant.now());
        when(repository.appendMessage(eq(sessionId), eq("user"), eq("hello"), anyString(), isNull(), anyMap()))
                .thenReturn(stored);

        AgentSessionStore store = new AgentSessionStore(redis, new ObjectMapper().findAndRegisterModules(), properties(), repository);

        assertThat(store.append(sessionId, "user", "hello")).isEqualTo(stored);
        verify(repository).appendMessage(eq(sessionId), eq("user"), eq("hello"), eq("COMPLETED"), isNull(), anyMap());
    }

    @Test
    void fallsBackToPostgresWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(lists.range(anyString(), anyLong(), anyLong())).thenThrow(new IllegalStateException("redis down"));
        AgentRepository repository = mock(AgentRepository.class);
        UUID sessionId = UUID.randomUUID();
        when(repository.recentMessages(sessionId, 30)).thenReturn(List.of(
                new AgentDtos.ConversationMessage(1, "assistant", "stored", "COMPLETED", null, Map.of(), Instant.now())));

        AgentSessionStore store = new AgentSessionStore(redis, new ObjectMapper().findAndRegisterModules(), properties(), repository);

        assertThat(store.messages(sessionId)).extracting(AgentSessionStore.Message::content).containsExactly("stored");
        verify(repository).recentMessages(sessionId, 30);
    }

    private AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxMessages(30);
        return properties;
    }
}

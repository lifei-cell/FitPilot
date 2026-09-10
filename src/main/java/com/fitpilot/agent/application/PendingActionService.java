package com.fitpilot.agent.application;

import com.fitpilot.agent.config.AgentProperties;
import com.fitpilot.agent.dto.AgentDtos;
import com.fitpilot.agent.infrastructure.AgentRepository;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PendingActionService {
    private final AgentRepository repository;
    private final AgentProperties properties;
    private final SecureRandom random = new SecureRandom();

    public PendingActionService(AgentRepository repository, AgentProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public List<AgentDtos.PendingActionSummary> list(long userId, UUID sessionId) {
        return repository.pendingActions(userId, sessionId);
    }

    public AgentDtos.ConfirmationTokenView rotateToken(long userId, UUID actionId) {
        String token = token();
        Instant expires = expiresAt();
        if (!repository.rotatePendingToken(actionId, userId, hash(token), expires)) {
            throw error(ErrorCode.AGENT_CONFIRMATION_INVALID, "pending action not found", HttpStatus.NOT_FOUND);
        }
        return new AgentDtos.ConfirmationTokenView(actionId, token, expires);
    }

    public AgentDtos.PendingActionView create(UUID executionId, long userId, String tool, Object payload,
                                               Object preview, List<String> warnings) {
        UUID id = UUID.randomUUID();
        String token = token();
        Instant expires = expiresAt();
        repository.createPending(id, executionId, userId, tool, payload, hash(token), expires, LocalDateTime.now());
        repository.toolCall(executionId, tool, payload,
                Map.of("pendingActionId", id, "confirmationRequired", true), "AWAITING_CONFIRMATION", 0);
        return new AgentDtos.PendingActionView(id, tool, token, expires, preview, warnings);
    }

    public AgentRepository.Pending requireConfirmable(long userId, UUID actionId, String token) {
        AgentRepository.Pending pending = repository.lockPending(actionId, userId)
                .orElseThrow(() -> error(ErrorCode.AGENT_CONFIRMATION_INVALID,
                        "confirmation not found", HttpStatus.NOT_FOUND));
        if (!"AWAITING_CONFIRMATION".equals(pending.status())) {
            throw error(ErrorCode.AGENT_ACTION_ALREADY_PROCESSED, "action already processed", HttpStatus.CONFLICT);
        }
        if (token == null || pending.expiresAt().isBefore(Instant.now())
                || !MessageDigest.isEqual(pending.confirmationHash().getBytes(StandardCharsets.UTF_8),
                hash(token).getBytes(StandardCharsets.UTF_8))) {
            throw error(ErrorCode.AGENT_CONFIRMATION_INVALID,
                    "confirmation is expired or invalid", HttpStatus.FORBIDDEN);
        }
        return pending;
    }

    public String subjectHash(long userId) {
        return hash("user:" + userId).substring(0, 16);
    }

    private Instant expiresAt() {
        return Instant.now().plusSeconds(properties.getConfirmationTtlSeconds());
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private BusinessException error(ErrorCode code, String message, HttpStatus status) {
        return new BusinessException(code, message, status);
    }
}

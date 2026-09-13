package com.fitpilot.workout.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.workout.dto.WorkoutDtos;
import com.fitpilot.workout.repository.WorkoutReceiptRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Durable acknowledgement and set insertion commit together, independent of Redis TTL. */
@Service
public class RecoverableSetService {
    private final WorkoutReceiptRepository receipts;
    private final WorkoutService workouts;
    private final ObjectMapper json;

    public RecoverableSetService(WorkoutReceiptRepository receipts, WorkoutService workouts, ObjectMapper json) {
        this.receipts = receipts;
        this.workouts = workouts;
        this.json = json;
    }

    @Transactional
    public WorkoutDtos.SetView add(long userId, long workoutId, long exerciseId,
                                  WorkoutDtos.SetRequest request, UUID operationId) {
        if (!receipts.lockOwned(userId, workoutId)) {
            throw new BusinessException(ErrorCode.WORKOUT_NOT_FOUND, "workout resource not found", HttpStatus.NOT_FOUND);
        }
        if (operationId == null) return workouts.addSet(userId, workoutId, exerciseId, request);
        try {
            String body = json.writeValueAsString(request);
            var receipt = receipts.find(workoutId, operationId);
            if (receipt.isPresent()) {
                var saved = receipt.get();
                if (saved.exerciseId() != exerciseId || !json.readTree(saved.request()).equals(json.readTree(body))) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                            "operation id already used with different set data", HttpStatus.CONFLICT);
                }
                return json.readValue(saved.response(), WorkoutDtos.SetView.class);
            }
            var result = workouts.addSet(userId, workoutId, exerciseId, request);
            receipts.insert(workoutId, operationId, exerciseId, body, json.writeValueAsString(result));
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("workout receipt serialization failed", exception);
        }
    }
}

package com.fitpilot.pr.controller;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.exercise.repository.ExerciseRepository;
import com.fitpilot.pr.application.LeaderboardService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/leaderboards")
public class LeaderboardController {
    private final LeaderboardService service;
    private final ExerciseRepository exercises;

    public LeaderboardController(LeaderboardService service, ExerciseRepository exercises) {
        this.service = service;
        this.exercises = exercises;
    }

    @GetMapping("/exercises/{exerciseId}")
    ApiResponse<List<LeaderboardService.Entry>> top(
            @PathVariable long exerciseId,
            @RequestParam(defaultValue = "ESTIMATED_1RM")
            @Pattern(regexp = "ESTIMATED_1RM|MAX_WEIGHT|MAX_VOLUME|THREE_RM|FIVE_RM|EIGHT_RM|TEN_RM") String type,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        if (exercises.findActive(exerciseId).isEmpty()) {
            throw new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "exercise not found", HttpStatus.NOT_FOUND);
        }
        return ApiResponse.success(service.top(exerciseId, type, limit));
    }
}

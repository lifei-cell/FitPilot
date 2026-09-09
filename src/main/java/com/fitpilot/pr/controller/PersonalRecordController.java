package com.fitpilot.pr.controller;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.security.CurrentUser;
import com.fitpilot.exercise.application.ExerciseService;
import com.fitpilot.pr.application.PersonalRecordService;
import com.fitpilot.pr.dto.PersonalRecordView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/personal-records")
public class PersonalRecordController {
    private final PersonalRecordService service;
    private final ExerciseService exercises;

    public PersonalRecordController(PersonalRecordService service, ExerciseService exercises) {
        this.service = service;
        this.exercises = exercises;
    }

    @GetMapping
    ApiResponse<List<PersonalRecordView>> all(Authentication auth) {
        return ApiResponse.success(service.current(CurrentUser.id(auth)));
    }

    @GetMapping("/exercises/{exerciseId}")
    ApiResponse<List<PersonalRecordView>> exercise(@PathVariable long exerciseId, Authentication auth) {
        ensureExercise(exerciseId);
        return ApiResponse.success(service.currentForExercise(CurrentUser.id(auth), exerciseId));
    }

    @GetMapping("/exercises/{exerciseId}/history")
    ApiResponse<List<PersonalRecordView>> history(@PathVariable long exerciseId, Authentication auth) {
        ensureExercise(exerciseId);
        return ApiResponse.success(service.history(CurrentUser.id(auth), exerciseId));
    }

    private void ensureExercise(long id) {
        if (exercises.findActive(id).isEmpty())
            throw new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "exercise not found", HttpStatus.NOT_FOUND);
    }
}

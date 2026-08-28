package com.fitpilot.pr.controller;

import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.security.CurrentUser;
import com.fitpilot.exercise.repository.ExerciseRepository;
import com.fitpilot.pr.dto.PersonalRecordView;
import com.fitpilot.pr.repository.PersonalRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/personal-records")
public class PersonalRecordController {
    private final PersonalRecordRepository repository;
    private final ExerciseRepository exercises;

    public PersonalRecordController(PersonalRecordRepository repository, ExerciseRepository exercises) {
        this.repository = repository;
        this.exercises = exercises;
    }

    @GetMapping
    ApiResponse<List<PersonalRecordView>> all(Authentication auth) {
        return ApiResponse.success(repository.findCurrent(CurrentUser.id(auth)).stream().map(PersonalRecordView::from).toList());
    }

    @GetMapping("/exercises/{exerciseId}")
    ApiResponse<List<PersonalRecordView>> exercise(@PathVariable long exerciseId, Authentication auth) {
        ensureExercise(exerciseId);
        return ApiResponse.success(repository.findCurrentForExercise(CurrentUser.id(auth), exerciseId)
                .stream().map(PersonalRecordView::from).toList());
    }

    @GetMapping("/exercises/{exerciseId}/history")
    ApiResponse<List<PersonalRecordView>> history(@PathVariable long exerciseId, Authentication auth) {
        ensureExercise(exerciseId);
        return ApiResponse.success(repository.findHistory(CurrentUser.id(auth), exerciseId)
                .stream().map(PersonalRecordView::from).toList());
    }

    private void ensureExercise(long id) {
        if (exercises.findActive(id).isEmpty())
            throw new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "exercise not found", HttpStatus.NOT_FOUND);
    }
}

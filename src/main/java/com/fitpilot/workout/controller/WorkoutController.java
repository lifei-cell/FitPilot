package com.fitpilot.workout.controller;

import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.common.security.CurrentUser;
import com.fitpilot.workout.application.WorkoutService;
import com.fitpilot.workout.dto.WorkoutDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Validated
@RestController
@RequestMapping("/api/v1/workouts")
public class WorkoutController {
    private final WorkoutService service;
    public WorkoutController(WorkoutService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<WorkoutDtos.WorkoutView> create(@Valid @RequestBody WorkoutDtos.CreateRequest request, Authentication auth) {
        return ApiResponse.success(service.create(CurrentUser.id(auth), request));
    }

    @GetMapping("/{id}")
    ApiResponse<WorkoutDtos.WorkoutView> get(@PathVariable long id, Authentication auth) {
        return ApiResponse.success(service.get(CurrentUser.id(auth), id));
    }

    @GetMapping("/active/current")
    ApiResponse<WorkoutDtos.WorkoutView> active(Authentication auth) {
        return ApiResponse.success(service.active(CurrentUser.id(auth)));
    }

    @GetMapping
    ApiResponse<PageResult<WorkoutDtos.WorkoutSummary>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            Authentication auth) {
        return ApiResponse.success(service.list(CurrentUser.id(auth), startDate, endDate, status, page, size));
    }

    @PostMapping("/{workoutId}/exercises")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<WorkoutDtos.ExerciseView> addExercise(@PathVariable long workoutId,
                                                       @Valid @RequestBody WorkoutDtos.AddExerciseRequest request,
                                                       Authentication auth) {
        return ApiResponse.success(service.addExercise(CurrentUser.id(auth), workoutId, request));
    }

    @PostMapping("/{workoutId}/exercises/{workoutExerciseId}/sets")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<WorkoutDtos.SetView> addSet(@PathVariable long workoutId, @PathVariable long workoutExerciseId,
                                            @Valid @RequestBody WorkoutDtos.SetRequest request, Authentication auth) {
        return ApiResponse.success(service.addSet(CurrentUser.id(auth), workoutId, workoutExerciseId, request));
    }

    @PutMapping("/{workoutId}/sets/{setId}")
    ApiResponse<WorkoutDtos.SetView> updateSet(@PathVariable long workoutId, @PathVariable long setId,
                                               @Valid @RequestBody WorkoutDtos.SetRequest request, Authentication auth) {
        return ApiResponse.success(service.updateSet(CurrentUser.id(auth), workoutId, setId, request));
    }

    @DeleteMapping("/{workoutId}/sets/{setId}")
    ApiResponse<Void> deleteSet(@PathVariable long workoutId, @PathVariable long setId, Authentication auth) {
        service.deleteSet(CurrentUser.id(auth), workoutId, setId);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/cancel")
    ApiResponse<WorkoutDtos.WorkoutView> cancel(@PathVariable long id, Authentication auth) {
        return ApiResponse.success(service.cancel(CurrentUser.id(auth), id));
    }

    @PostMapping("/{id}/complete")
    ApiResponse<WorkoutDtos.CompleteView> complete(@PathVariable long id, Authentication auth) {
        return ApiResponse.success(service.complete(CurrentUser.id(auth), id));
    }
}

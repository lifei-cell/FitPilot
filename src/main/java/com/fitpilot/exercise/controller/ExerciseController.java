package com.fitpilot.exercise.controller;

import com.fitpilot.common.response.ApiResponse;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.exercise.application.ExerciseService;
import com.fitpilot.exercise.dto.ExerciseView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/exercises")
public class ExerciseController {
    private final ExerciseService service;
    public ExerciseController(ExerciseService service) { this.service = service; }

    @GetMapping
    ApiResponse<PageResult<ExerciseView>> list(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String equipment,
                                               @RequestParam(required = false) String muscle,
                                               @RequestParam(defaultValue = "1") @Min(1) long page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size) {
        return ApiResponse.success(service.search(keyword, category, equipment, muscle, page, size));
    }

    @GetMapping("/{exerciseId}")
    ApiResponse<ExerciseView> get(@PathVariable long exerciseId) { return ApiResponse.success(service.get(exerciseId)); }
}

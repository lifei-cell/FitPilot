package com.fitpilot.exercise.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.exercise.domain.Exercise;
import com.fitpilot.exercise.dto.ExerciseView;
import com.fitpilot.exercise.repository.ExerciseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExerciseService {
    private final ExerciseRepository repository;
    public ExerciseService(ExerciseRepository repository) { this.repository = repository; }

    public PageResult<ExerciseView> search(String keyword, String category, String equipment, String muscle,
                                            long page, long size) {
        Page<Exercise> result = repository.search(blankToNull(keyword), upper(category), upper(equipment), upper(muscle), page, size);
        return PageResult.of(result.getRecords().stream().map(ExerciseView::from).toList(), result.getTotal(), page, size);
    }

    public ExerciseView get(long id) {
        return repository.findActive(id).map(ExerciseView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "exercise not found", HttpStatus.NOT_FOUND));
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String upper(String value) { String normalized = blankToNull(value); return normalized == null ? null : normalized.toUpperCase(); }
}

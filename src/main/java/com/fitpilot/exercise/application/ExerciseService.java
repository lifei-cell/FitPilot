package com.fitpilot.exercise.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.response.PageResult;
import com.fitpilot.exercise.domain.Exercise;
import com.fitpilot.exercise.dto.ExerciseView;
import com.fitpilot.exercise.repository.ExerciseRepository;
import com.fitpilot.infrastructure.performance.TwoLevelCache;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExerciseService {
    private final ExerciseRepository repository;
    private final TwoLevelCache cache;
    public ExerciseService(ExerciseRepository repository, TwoLevelCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public PageResult<ExerciseView> search(String keyword, String category, String equipment, String muscle,
                                            long page, long size) {
        Page<Exercise> result = repository.search(blankToNull(keyword), upper(category), upper(equipment), upper(muscle), page, size);
        return PageResult.of(result.getRecords().stream().map(ExerciseView::from).toList(), result.getTotal(), page, size);
    }

    public ExerciseView get(long id) {
        return cache.get("exercise", String.valueOf(id), ExerciseView.class,
                        () -> repository.findActive(id).map(ExerciseView::from))
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND, "exercise not found", HttpStatus.NOT_FOUND));
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String upper(String value) { String normalized = blankToNull(value); return normalized == null ? null : normalized.toUpperCase(); }
}

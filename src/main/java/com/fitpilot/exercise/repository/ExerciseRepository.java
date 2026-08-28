package com.fitpilot.exercise.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitpilot.exercise.domain.Exercise;
import com.fitpilot.exercise.infrastructure.ExerciseMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class ExerciseRepository {
    private final ExerciseMapper mapper;
    public ExerciseRepository(ExerciseMapper mapper) { this.mapper = mapper; }

    public Optional<Exercise> findActive(long id) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<Exercise>().eq("id", id).eq("status", 1)));
    }

    public List<Exercise> findActiveByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return mapper.selectList(new QueryWrapper<Exercise>().in("id", ids).eq("status", 1));
    }

    public Page<Exercise> search(String keyword, String category, String equipment, String muscle, long page, long size) {
        QueryWrapper<Exercise> query = new QueryWrapper<Exercise>().eq("status", 1)
                .eq(category != null, "category", category)
                .eq(equipment != null, "equipment", equipment)
                .and(muscle != null, q -> q.eq("primary_muscle", muscle).or().like("secondary_muscles", muscle))
                .and(keyword != null, q -> q.like("name", keyword).or().like("english_name", keyword))
                .orderByAsc("id");
        return mapper.selectPage(Page.of(page, size), query);
    }
}

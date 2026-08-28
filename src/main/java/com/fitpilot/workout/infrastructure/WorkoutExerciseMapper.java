package com.fitpilot.workout.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitpilot.workout.domain.WorkoutExercise;
import org.apache.ibatis.annotations.Select;

public interface WorkoutExerciseMapper extends BaseMapper<WorkoutExercise> {
    @Select("SELECT id FROM workout_exercise WHERE id = #{id} FOR UPDATE")
    Long lockById(long id);
}

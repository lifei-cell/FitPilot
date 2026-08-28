package com.fitpilot.exercise.dto;

import com.fitpilot.exercise.domain.Exercise;

public record ExerciseView(long id, String name, String englishName, String category, String equipment,
                           String difficulty, String primaryMuscle, String secondaryMuscles,
                           String description, String instructions) {
    public static ExerciseView from(Exercise e) {
        return new ExerciseView(e.id, e.name, e.englishName, e.category, e.equipment, e.difficulty,
                e.primaryMuscle, e.secondaryMuscles, e.description, e.instructions);
    }
}

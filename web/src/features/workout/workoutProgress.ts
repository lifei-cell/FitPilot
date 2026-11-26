import type { WorkoutExercise } from "../../api/types";

export function workoutProgress(exercises: WorkoutExercise[]) {
  let plannedSets = 0;
  let completedPlannedSets = 0;
  let nextExercise: WorkoutExercise | undefined;

  const items = exercises.map((exercise) => {
    const completed = exercise.sets.filter(
      (set) => !set.isWarmup && set.reps > 0 && Boolean(set.completedAt),
    ).length;
    const target = exercise.targetSets ?? 0;

    if (target > 0) {
      plannedSets += target;
      completedPlannedSets += Math.min(completed, target);
      if (completed < target) nextExercise ??= exercise;
    }

    return { exercise, completed, target };
  });

  return { items, plannedSets, completedPlannedSets, nextExercise };
}

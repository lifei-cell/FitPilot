import { ArrowDown } from "lucide-react";
import type { WorkoutExercise } from "../../api/types";
import "./workout-progress.css";

type Props = {
  plannedSets: number;
  completedPlannedSets: number;
  nextExercise?: Pick<WorkoutExercise, "id" | "exerciseName">;
};

export function workoutExerciseHeadingId(id: number) {
  return `workout-exercise-${id}`;
}

export function WorkoutProgressSummary({
  plannedSets,
  completedPlannedSets,
  nextExercise,
}: Props) {
  if (plannedSets === 0) return null;

  function locateExercise(id: number) {
    const heading = document.getElementById(workoutExerciseHeadingId(id));
    heading?.focus({ preventScroll: true });
    heading?.scrollIntoView({ block: "start" });
  }

  return (
    <div className="workout-progress-summary">
      <div className="workout-progress-total">
        <p>计划组数 <strong>{completedPlannedSets} / {plannedSets} 组</strong></p>
        <progress aria-label="计划组数进度" value={completedPlannedSets} max={plannedSets} />
      </div>
      {nextExercise ? (
        <button
          type="button"
          className="secondary-button"
          onClick={() => locateExercise(nextExercise.id)}
        >
          <ArrowDown size={15} aria-hidden="true" />
          定位待练动作：{nextExercise.exerciseName}
        </button>
      ) : (
        <span className="workout-progress-complete">计划组数已达成</span>
      )}
    </div>
  );
}

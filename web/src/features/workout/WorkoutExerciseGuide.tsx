import { useQuery } from "@tanstack/react-query";
import { BookOpen } from "lucide-react";
import { useId } from "react";
import { api, ApiError } from "../../api/client";
import type { Exercise, WorkoutExercise } from "../../api/types";
import { ExerciseMediaPanel } from "../exercise/ExerciseMediaPanel";
import "./workout-exercise-guide.css";

type Props = {
  exercise: Pick<WorkoutExercise, "exerciseId" | "exerciseName">;
  expanded: boolean;
  onToggle(): void;
};

export function WorkoutExerciseGuide({ exercise, expanded, onToggle }: Props) {
  const contentId = useId();
  const label = expanded ? "收起动作教学" : "查看动作教学";

  return (
    <div className="workout-exercise-guide">
      <button
        type="button"
        className="secondary-button"
        aria-label={`${label}：${exercise.exerciseName}`}
        aria-expanded={expanded}
        aria-controls={contentId}
        onClick={onToggle}
      >
        <BookOpen size={15} aria-hidden="true" />
        {label}
      </button>
      <div id={contentId} hidden={!expanded}>
        {expanded && <GuideContent exerciseId={exercise.exerciseId} />}
      </div>
    </div>
  );
}

function GuideContent({ exerciseId }: { exerciseId: number }) {
  const detail = useQuery({
    queryKey: ["exercise-detail", exerciseId],
    queryFn: ({ signal }) => api<Exercise>(`/exercises/${exerciseId}`, { signal }),
    retry: false,
  });

  if (detail.data) {
    return (
      <div className="workout-exercise-guide-content">
        {detail.data.instructions ? (
          <p><strong>执行要点</strong> {detail.data.instructions}</p>
        ) : (
          <p className="subtle">暂无文字要点。</p>
        )}
        <ExerciseMediaPanel exercise={detail.data} />
      </div>
    );
  }

  if (detail.isError) {
    if (detail.error instanceof ApiError && detail.error.status === 404) {
      return <p className="subtle">动作库中暂无这项动作的教学。</p>;
    }
    return (
      <div role="alert">
        <p className="form-error">暂时无法加载动作教学。</p>
        <button
          type="button"
          className="secondary-button"
          disabled={detail.fetchStatus !== "idle"}
          onClick={() => void detail.refetch()}
        >
          重试加载教学
        </button>
      </div>
    );
  }

  return (
    <p role="status">
      {detail.fetchStatus === "paused" ? "联网后可加载动作教学。" : "正在加载动作教学…"}
    </p>
  );
}

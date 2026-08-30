import { Dumbbell, Trash2, X } from "lucide-react";
import type { Exercise } from "../../api/types";
import { ExerciseSearchPicker } from "./ExerciseSearchPicker";
import type { DraftDay, DraftExercise } from "./planBuilderModel";

type Props = {
  day: DraftDay;
  index: number;
  canRemove: boolean;
  onChange(patch: Partial<DraftDay>): void;
  onRemove(): void;
  onAddExercise(exercise: Exercise): void;
  onChangeExercise(key: string, patch: Partial<DraftExercise>): void;
  onRemoveExercise(key: string): void;
};

export function PlanDayEditor({
  day,
  index,
  canRemove,
  onChange,
  onRemove,
  onAddExercise,
  onChangeExercise,
  onRemoveExercise,
}: Props) {
  return (
    <section className="plan-day-editor">
      <header>
        <span>DAY {index + 1}</span>
        <input
          value={day.name}
          maxLength={100}
          aria-label={`第 ${index + 1} 个训练日名称`}
          onChange={(event) => onChange({ name: event.target.value })}
        />
        <button
          type="button"
          className="plan-icon-button danger"
          disabled={!canRemove}
          aria-label={`删除第 ${index + 1} 个训练日`}
          onClick={onRemove}
        >
          <Trash2 size={15} />
        </button>
      </header>
      <textarea
        value={day.notes}
        rows={2}
        maxLength={500}
        placeholder="训练日说明（可选）"
        onChange={(event) => onChange({ notes: event.target.value })}
      />

      <div className="plan-exercise-list">
        {day.exercises.map((item, exerciseIndex) => (
          <article key={item.key} className="plan-exercise-editor">
            <div className="plan-exercise-title">
              <Dumbbell size={16} />
              <span>
                <strong>{item.exercise.name}</strong>
                <small>动作 {exerciseIndex + 1}</small>
              </span>
              <button
                type="button"
                className="plan-icon-button"
                aria-label={`移除${item.exercise.name}`}
                onClick={() => onRemoveExercise(item.key)}
              >
                <X size={15} />
              </button>
            </div>
            <div className="plan-exercise-fields">
              <label>
                组数
                <input
                  type="number"
                  min="1"
                  max="20"
                  value={item.targetSets}
                  onChange={(event) =>
                    onChangeExercise(item.key, {
                      targetSets: Number(event.target.value),
                    })
                  }
                />
              </label>
              <label>
                最少次数
                <input
                  type="number"
                  min="1"
                  max="100"
                  value={item.targetRepsMin}
                  onChange={(event) =>
                    onChangeExercise(item.key, {
                      targetRepsMin: Number(event.target.value),
                    })
                  }
                />
              </label>
              <label>
                最多次数
                <input
                  type="number"
                  min="1"
                  max="100"
                  value={item.targetRepsMax}
                  onChange={(event) =>
                    onChangeExercise(item.key, {
                      targetRepsMax: Number(event.target.value),
                    })
                  }
                />
              </label>
              <label>
                RPE
                <input
                  type="number"
                  min="1"
                  max="10"
                  step=".5"
                  value={item.targetRpe}
                  onChange={(event) =>
                    onChangeExercise(item.key, {
                      targetRpe:
                        event.target.value === ""
                          ? ""
                          : Number(event.target.value),
                    })
                  }
                />
              </label>
              <label>
                休息秒数
                <input
                  type="number"
                  min="0"
                  max="3600"
                  value={item.restSeconds}
                  onChange={(event) =>
                    onChangeExercise(item.key, {
                      restSeconds:
                        event.target.value === ""
                          ? ""
                          : Number(event.target.value),
                    })
                  }
                />
              </label>
            </div>
            <input
              className="plan-exercise-notes"
              value={item.notes}
              maxLength={255}
              placeholder="动作提示（可选）"
              onChange={(event) =>
                onChangeExercise(item.key, { notes: event.target.value })
              }
            />
          </article>
        ))}
        {day.exercises.length === 0 && (
          <p className="plan-day-empty">搜索并添加这个训练日的第一个动作。</p>
        )}
      </div>

      <ExerciseSearchPicker
        selectedIds={day.exercises.map((item) => item.exercise.id)}
        onSelect={onAddExercise}
      />
    </section>
  );
}

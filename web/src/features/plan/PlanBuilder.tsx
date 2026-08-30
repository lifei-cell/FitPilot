import { Plus, Save, X } from "lucide-react";
import { useState, type FormEvent } from "react";
import type { Exercise, PlanCreateInput } from "../../api/types";
import { PlanDayEditor } from "./PlanDayEditor";
import {
  initialPlanDraft,
  newDay,
  newExercise,
  toPlanCreateInput,
  validatePlanDraft,
  type DraftDay,
  type DraftExercise,
  type PlanDraft,
} from "./planBuilderModel";
import "./plan-builder.css";

type Props = {
  submitting: boolean;
  serverError?: string;
  initialDraft?: PlanDraft;
  submitLabel?: string;
  onSubmit(payload: PlanCreateInput): void;
  onCancel(): void;
};

export function PlanBuilder({
  submitting,
  serverError,
  initialDraft,
  submitLabel = "保存为草稿",
  onSubmit,
  onCancel,
}: Props) {
  const [draft, setDraft] = useState(() => initialDraft ?? initialPlanDraft());
  const [validationError, setValidationError] = useState("");

  function updateDay(key: string, patch: Partial<DraftDay>) {
    setDraft((current) => ({
      ...current,
      days: current.days.map((day) =>
        day.key === key ? { ...day, ...patch } : day,
      ),
    }));
  }

  function addExercise(dayKey: string, exercise: Exercise) {
    setDraft((current) => ({
      ...current,
      days: current.days.map((day) =>
        day.key === dayKey &&
        !day.exercises.some((item) => item.exercise.id === exercise.id)
          ? { ...day, exercises: [...day.exercises, newExercise(exercise)] }
          : day,
      ),
    }));
  }

  function updateExercise(
    dayKey: string,
    exerciseKey: string,
    patch: Partial<DraftExercise>,
  ) {
    setDraft((current) => ({
      ...current,
      days: current.days.map((day) =>
        day.key === dayKey
          ? {
              ...day,
              exercises: day.exercises.map((exercise) =>
                exercise.key === exerciseKey
                  ? { ...exercise, ...patch }
                  : exercise,
              ),
            }
          : day,
      ),
    }));
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const issue = validatePlanDraft(draft);
    setValidationError(issue);
    if (!issue) onSubmit(toPlanCreateInput(draft));
  }

  return (
    <form className="plan-builder" onSubmit={submit}>
      <div className="plan-builder-meta">
        <label className="plan-name-field">
          计划名称
          <input
            value={draft.name}
            required
            maxLength={100}
            placeholder="力量增长 · 12 周"
            onChange={(event) =>
              setDraft((current) => ({ ...current, name: event.target.value }))
            }
          />
        </label>
        <label>
          训练目标
          <select
            value={draft.goal}
            onChange={(event) =>
              setDraft((current) => ({ ...current, goal: event.target.value }))
            }
          >
            <option value="STRENGTH">力量</option>
            <option value="MUSCLE_GAIN">增肌</option>
            <option value="FAT_LOSS">减脂</option>
            <option value="GENERAL_FITNESS">综合体能</option>
          </select>
        </label>
        <label>
          周期（周）
          <input
            type="number"
            min="1"
            max="104"
            value={draft.durationWeeks}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                durationWeeks: Number(event.target.value),
              }))
            }
          />
        </label>
        <label className="plan-description-field">
          计划说明
          <textarea
            value={draft.description}
            rows={2}
            placeholder="计划目标与执行说明（可选）"
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                description: event.target.value,
              }))
            }
          />
        </label>
      </div>

      <div className="plan-days-editor">
        {draft.days.map((day, index) => (
          <PlanDayEditor
            key={day.key}
            day={day}
            index={index}
            canRemove={draft.days.length > 1}
            onChange={(patch) => updateDay(day.key, patch)}
            onRemove={() =>
              setDraft((current) => ({
                ...current,
                days: current.days.filter((item) => item.key !== day.key),
              }))
            }
            onAddExercise={(exercise) => addExercise(day.key, exercise)}
            onChangeExercise={(exerciseKey, patch) =>
              updateExercise(day.key, exerciseKey, patch)
            }
            onRemoveExercise={(exerciseKey) =>
              updateDay(day.key, {
                exercises: day.exercises.filter(
                  (exercise) => exercise.key !== exerciseKey,
                ),
              })
            }
          />
        ))}
      </div>

      <button
        type="button"
        className="secondary-button plan-add-day"
        disabled={draft.days.length >= 7}
        onClick={() =>
          setDraft((current) => ({
            ...current,
            days: [...current.days, newDay(current.days.length + 1)],
          }))
        }
      >
        <Plus size={16} />
        添加训练日（{draft.days.length}/7）
      </button>

      {(validationError || serverError) && (
        <p className="form-error plan-builder-error">
          {validationError || serverError}
        </p>
      )}
      <footer className="plan-builder-actions">
        <button className="primary-button" disabled={submitting}>
          <Save size={17} />
          {submitting ? "保存中…" : submitLabel}
        </button>
        <button
          type="button"
          className="secondary-button"
          disabled={submitting}
          onClick={onCancel}
        >
          <X size={16} />
          取消
        </button>
      </footer>
    </form>
  );
}

import type { Exercise, Plan, PlanCreateInput } from "../../api/types";

export type DraftExercise = {
  key: string;
  exercise: Exercise;
  targetSets: number;
  targetRepsMin: number;
  targetRepsMax: number;
  targetRpe: number | "";
  restSeconds: number | "";
  notes: string;
};

export type DraftDay = {
  key: string;
  name: string;
  notes: string;
  exercises: DraftExercise[];
};

export type PlanDraft = {
  name: string;
  description: string;
  goal: string;
  durationWeeks: number;
  days: DraftDay[];
};

function key() {
  return crypto.randomUUID();
}

export function newDay(index: number): DraftDay {
  return {
    key: key(),
    name: `训练日 ${index}`,
    notes: "",
    exercises: [],
  };
}

export function newExercise(exercise: Exercise): DraftExercise {
  return {
    key: key(),
    exercise,
    targetSets: 4,
    targetRepsMin: 5,
    targetRepsMax: 8,
    targetRpe: 8,
    restSeconds: 120,
    notes: "",
  };
}

export function initialPlanDraft(): PlanDraft {
  return {
    name: "",
    description: "",
    goal: "STRENGTH",
    durationWeeks: 12,
    days: [newDay(1)],
  };
}

export function planToDraft(plan: Plan): PlanDraft {
  return {
    name: plan.name,
    description: plan.description ?? "",
    goal: plan.goal,
    durationWeeks: plan.durationWeeks,
    days: [...plan.days]
      .sort((left, right) => left.dayNumber - right.dayNumber)
      .map((day) => ({
        key: key(),
        name: day.name,
        notes: day.notes ?? "",
        exercises: [...day.exercises]
          .sort((left, right) => left.sequence - right.sequence)
          .map((exercise) => ({
            key: key(),
            exercise: {
              id: exercise.exerciseId,
              name: exercise.exerciseName ?? `动作 #${exercise.exerciseId}`,
            },
            targetSets: exercise.targetSets,
            targetRepsMin: exercise.targetRepsMin,
            targetRepsMax: exercise.targetRepsMax,
            targetRpe: exercise.targetRpe ?? "",
            restSeconds: exercise.restSeconds ?? "",
            notes: exercise.notes ?? "",
          })),
      })),
  };
}

export function validatePlanDraft(draft: PlanDraft) {
  if (!draft.name.trim()) return "请输入计划名称。";
  if (draft.days.length === 0) return "计划至少需要一个训练日。";
  for (let index = 0; index < draft.days.length; index += 1) {
    const day = draft.days[index];
    if (!day.name.trim()) return `第 ${index + 1} 个训练日缺少名称。`;
    if (day.exercises.length === 0)
      return `“${day.name}”至少需要添加一个动作。`;
    if (
      day.exercises.some(
        (exercise) => exercise.targetRepsMin > exercise.targetRepsMax,
      )
    )
      return `“${day.name}”存在最小次数大于最大次数的动作。`;
  }
  return "";
}

export function toPlanCreateInput(draft: PlanDraft): PlanCreateInput {
  return {
    name: draft.name.trim(),
    description: draft.description.trim() || undefined,
    goal: draft.goal,
    durationWeeks: draft.durationWeeks,
    days: draft.days.map((day, dayIndex) => ({
      dayNumber: dayIndex + 1,
      name: day.name.trim(),
      notes: day.notes.trim() || undefined,
      exercises: day.exercises.map((exercise, exerciseIndex) => ({
        exerciseId: exercise.exercise.id,
        sequence: exerciseIndex + 1,
        targetSets: exercise.targetSets,
        targetRepsMin: exercise.targetRepsMin,
        targetRepsMax: exercise.targetRepsMax,
        targetRpe:
          exercise.targetRpe === "" ? undefined : exercise.targetRpe,
        restSeconds:
          exercise.restSeconds === "" ? undefined : exercise.restSeconds,
        notes: exercise.notes.trim() || undefined,
      })),
    })),
  };
}

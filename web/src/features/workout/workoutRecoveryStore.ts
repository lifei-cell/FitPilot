import { z } from "zod";
import type { Workout, WorkoutSetInput } from "../../api/types";

export const RECOVERY_CHANGED = "fitpilot:workout-recovery";
const prefix = "fitpilot:workout:v1:";
const input = z.object({ weightKg: z.number().nonnegative(), reps: z.number().int().positive(),
  rpe: z.number().min(1).max(10).optional(), rir: z.number().min(0).max(10).optional(),
  isWarmup: z.boolean(), isFailure: z.boolean() });
const operationSchema = z.object({ id: z.string().uuid(), workoutId: z.number().positive(),
  exerciseId: z.number().positive(), createdAt: z.string(), payload: input });
export type SetOperation = z.infer<typeof operationSchema>;
const draftSchema = z.object({ id: z.string().uuid(), weight: z.string(), reps: z.string(), rpe: z.string() });
export type SetDraft = z.infer<typeof draftSchema>;
const snapshotSchema = z.object({ id: z.number().positive(), name: z.string(), status: z.string(),
  startedAt: z.string(), exercises: z.array(z.object({ id: z.number().positive(), exerciseId: z.number().positive(),
    exerciseName: z.string(), sequence: z.number(), sets: z.array(z.object({ id: z.number(), setNumber: z.number(),
      weightKg: z.number().nullable(), reps: z.number(), isWarmup: z.boolean(), isFailure: z.boolean(),
    }).passthrough()),
  }).passthrough()),
}).passthrough();

function key(owner: string, suffix: string) { return `${prefix}${encodeURIComponent(owner)}:${suffix}`; }
function read<T>(name: string, schema: z.ZodType<T>): T | null {
  const text = localStorage.getItem(name);
  if (text === null) return null;
  try { return schema.parse(JSON.parse(text)); }
  catch { throw new Error("本机训练数据无法读取，已保留原数据；请勿清除浏览器数据。"); }
}
function changed() { window.dispatchEvent(new Event(RECOVERY_CHANGED)); }
function write(name: string, value: unknown) {
  try { localStorage.setItem(name, JSON.stringify(value)); }
  catch { throw new Error("本机保存失败，可能存储空间不足。当前内容尚未安全保存，请勿退出。"); }
  changed();
}
export function snapshot(owner: string): Workout | null {
  return read(key(owner, "snapshot"), snapshotSchema) as Workout | null;
}
export function saveSnapshot(owner: string, workout: Workout) {
  write(key(owner, "snapshot"), workout);
}
export function clearSnapshot(owner: string, workoutId: number) {
  if (snapshot(owner)?.id === workoutId) localStorage.removeItem(key(owner, "snapshot"));
  changed();
}
export function operations(owner: string, workoutId?: number): SetOperation[] {
  const start = key(owner, "set:");
  const result: SetOperation[] = [];
  for (let index = 0; index < localStorage.length; index++) {
    const name = localStorage.key(index);
    if (!name?.startsWith(start)) continue;
    const operation = read(name, operationSchema);
    if (operation && (workoutId === undefined || operation.workoutId === workoutId)) result.push(operation);
  }
  return result.sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id));
}
export function readDraft(owner: string, workoutId: number, exerciseId: number): SetDraft | null {
  const draft = read(key(owner, `draft:${workoutId}:${exerciseId}`), draftSchema);
  return draft && !localStorage.getItem(key(owner, `set:${draft.id}`)) ? draft : null;
}
export function saveDraft(owner: string, workoutId: number, exerciseId: number, draft: SetDraft) {
  write(key(owner, `draft:${workoutId}:${exerciseId}`), draft);
}
export function drafts(owner: string, workoutId: number): SetDraft[] {
  const start = key(owner, `draft:${workoutId}:`);
  const result: SetDraft[] = [];
  for (let index = 0; index < localStorage.length; index++) {
    const name = localStorage.key(index);
    if (!name?.startsWith(start)) continue;
    const draft = read(name, draftSchema);
    if (draft && (draft.weight || draft.reps || draft.rpe) && !localStorage.getItem(key(owner, `set:${draft.id}`))) result.push(draft);
  }
  return result;
}
export function exportRecovery(owner: string) {
  const data: Record<string, unknown> = {};
  for (let index = 0; index < localStorage.length; index++) {
    const name = localStorage.key(index);
    if (name?.startsWith(key(owner, ""))) data[name] = localStorage.getItem(name);
  }
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = "fitpilot-training-recovery.json";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function clearDraft(owner: string, workoutId: number, exerciseId: number, operationId: string) {
  const name = key(owner, `draft:${workoutId}:${exerciseId}`);
  if (read(name, draftSchema)?.id === operationId) localStorage.removeItem(name);
  changed();
}
export function enqueue(owner: string, workoutId: number, exerciseId: number, id: string, payload: WorkoutSetInput) {
  const name = key(owner, `set:${id}`);
  const existing = read(name, operationSchema);
  if (existing) {
    if (existing.workoutId !== workoutId || existing.exerciseId !== exerciseId
      || JSON.stringify(existing.payload) !== JSON.stringify(payload)) throw new Error("这条记录已在等待同步，请先核对。");
  } else write(name, operationSchema.parse({ id, workoutId, exerciseId, payload, createdAt: new Date().toISOString() }));
  clearDraft(owner, workoutId, exerciseId, id);
}
export function acknowledge(owner: string, operation: SetOperation) {
  clearDraft(owner, operation.workoutId, operation.exerciseId, operation.id);
  localStorage.removeItem(key(owner, `set:${operation.id}`));
  changed();
}

/** Each confirmed set has its own immutable key, so tabs cannot overwrite each other's queues. */
export function withPending(workout: Workout, pending: SetOperation[]): Workout {
  return { ...workout, exercises: workout.exercises.map((exercise) => ({ ...exercise,
    sets: [...exercise.sets, ...pending.filter((op) => op.exerciseId === exercise.id
      && !exercise.sets.some((set) => set.clientOperationId === op.id)).map((op, index) => ({
      ...op.payload, id: -(index + 1), setNumber: Math.max(0, ...exercise.sets.map((set) => set.setNumber)) + index + 1,
      completedAt: op.createdAt, localOperationId: op.id,
    }))],
  })) };
}

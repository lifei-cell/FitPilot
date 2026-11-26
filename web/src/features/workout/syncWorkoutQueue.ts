import { accessTokenSubject, api, ApiError } from "../../api/client";
import type { Workout, WorkoutSet } from "../../api/types";
import {
  acknowledge, operations, recordSyncConflict, saveSnapshot, snapshot, syncConflict, type SetOperation,
} from "./workoutRecoveryStore";

const running = new Map<string, Promise<void>>();
const conflictStatuses = new Set([400, 403, 404, 409, 422]);

/** A lost HTTP acknowledgement is safe to retry: the operation ID is persisted before sending. */
async function send(owner: string, operation: SetOperation) {
  if (accessTokenSubject() !== owner) throw new Error("请登录原账户后继续同步，本机记录已保留。");
  await api<WorkoutSet>(`/workouts/${operation.workoutId}/exercises/${operation.exerciseId}/sets`, {
    method: "POST", body: JSON.stringify(operation.payload), signal: AbortSignal.timeout(15_000),
    headers: { "Client-Operation-Id": operation.id },
  });
  if (accessTokenSubject() !== owner) return;
  // Read server truth before removing the local operation, including edits made elsewhere.
  const current = await api<Workout>(`/workouts/${operation.workoutId}`, { signal: AbortSignal.timeout(15_000) });
  if (accessTokenSubject() !== owner) return;
  // A background acknowledgement must not replace another workout's active snapshot.
  if (snapshot(owner)?.id === current.id) saveSnapshot(owner, current);
  acknowledge(owner, operation);
}

export function syncWorkoutQueue(owner: string, retryConflicts = false): Promise<void> {
  const existing = running.get(owner);
  if (existing) return existing;
  const run = async () => {
    const blockedWorkouts = new Set<number>();
    while (navigator.onLine && accessTokenSubject() === owner) {
      const operation = operations(owner).find(({ workoutId }) =>
        !blockedWorkouts.has(workoutId) && (retryConflicts || syncConflict(owner, workoutId) === null));
      if (!operation) return;
      try {
        await send(owner, operation);
      } catch (error) {
        if (!(error instanceof ApiError) || !conflictStatuses.has(error.status)) throw error;
        if (accessTokenSubject() !== owner) return;
        // Preserve order within the failed workout while draining other workouts.
        blockedWorkouts.add(operation.workoutId);
        recordSyncConflict(owner, operation.workoutId, error.message);
      }
    }
  };
  const promise = (async () => {
    if (navigator.locks) await navigator.locks.request(`fitpilot-workout-sync:${owner}`, run);
    else await run();
  })()
    .finally(() => running.delete(owner));
  running.set(owner, promise);
  return promise;
}

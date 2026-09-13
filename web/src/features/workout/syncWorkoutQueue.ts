import { accessTokenSubject, api, ApiError } from "../../api/client";
import type { Workout, WorkoutSet } from "../../api/types";
import { acknowledge, operations, saveSnapshot, type SetOperation } from "./workoutRecoveryStore";

const running = new Map<string, Promise<void>>();
export class WorkoutSyncConflict extends Error {}

/** A lost HTTP acknowledgement is safe to retry: the operation ID is persisted before sending. */
async function send(owner: string, operation: SetOperation) {
  if (accessTokenSubject() !== owner) throw new Error("请登录原账户后继续同步，本机记录已保留。");
  await api<WorkoutSet>(`/workouts/${operation.workoutId}/exercises/${operation.exerciseId}/sets`, {
    method: "POST", body: JSON.stringify(operation.payload), signal: AbortSignal.timeout(15_000),
    headers: { "Client-Operation-Id": operation.id },
  });
  // Read server truth before removing the local operation, including edits made elsewhere.
  const current = await api<Workout>(`/workouts/${operation.workoutId}`, { signal: AbortSignal.timeout(15_000) });
  if (accessTokenSubject() !== owner) return;
  saveSnapshot(owner, current);
  acknowledge(owner, operation);
}

export function syncWorkoutQueue(owner: string): Promise<void> {
  const existing = running.get(owner);
  if (existing) return existing;
  const run = async () => {
    while (operations(owner).length) {
      const operation = operations(owner)[0];
      if (!navigator.onLine || accessTokenSubject() !== owner) return;
      try { await send(owner, operation); }
      catch (error) {
        if (error instanceof ApiError && [400, 403, 404, 409, 422].includes(error.status)) {
          throw new WorkoutSyncConflict(`同步暂停：${error.message}。记录仍在本机，请核对训练状态后重试。`, { cause: error });
        }
        throw error;
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

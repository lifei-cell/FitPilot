import { useCallback, useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { accessTokenSubject, api, ApiError } from "../../api/client";
import type { Workout, WorkoutSetInput } from "../../api/types";
import { clearSnapshot, drafts, enqueue, operations, RECOVERY_CHANGED, saveSnapshot, snapshot, syncConflict, withPending } from "./workoutRecoveryStore";
import { syncWorkoutQueue } from "./syncWorkoutQueue";

export function useWorkoutRecovery() {
  const owner = accessTokenSubject();
  const client = useQueryClient();
  const [, refreshLocal] = useState(0);
  const [error, setError] = useState("");
  const [syncing, setSyncing] = useState(false);
  const [online, setOnline] = useState(navigator.onLine);
  let cached: Workout | null = null;
  let pending: ReturnType<typeof operations> = [];
  let storageError = "";
  try {
    if (owner) {
      cached = snapshot(owner);
      pending = operations(owner);
    }
  } catch (failure) { storageError = (failure as Error).message; }

  const active = useQuery({
    queryKey: ["active-workout"],
    queryFn: async () => {
      try {
        const result = await api<Workout>("/workouts/active/current");
        if (owner && accessTokenSubject() === owner) {
          saveSnapshot(owner, result);
        }
        return result;
      } catch (failure) {
        if (failure instanceof ApiError && failure.status === 404 && owner && accessTokenSubject() === owner) {
          const local = snapshot(owner);
          if (local) {
            if (!operations(owner, local.id).length && !drafts(owner, local.id).length) clearSnapshot(owner, local.id);
            else {
              const previous = await api<Workout>(`/workouts/${local.id}`);
              if (accessTokenSubject() === owner && snapshot(owner)?.id === local.id) {
                saveSnapshot(owner, previous);
              }
            }
          }
        }
        throw failure;
      }
    },
    retry: false,
  });

  const sync = useCallback(async (manual = true) => {
    if (!owner || accessTokenSubject() !== owner || !navigator.onLine) return;
    try {
      if (!operations(owner).length) return;
      setSyncing(true);
      await syncWorkoutQueue(owner, manual);
      setError("");
    } catch (failure) {
      setError(failure instanceof ApiError && failure.status === 401
        ? "登录已过期，请重新登录原账户；本机记录已保留。"
        : failure instanceof Error ? failure.message : "暂时无法同步，本机记录已保留。");
    } finally { setSyncing(false); refreshLocal((value) => value + 1); }
  }, [owner]);

  // The dashboard may already have populated the query cache without running this page's queryFn.
  useEffect(() => {
    try {
      if (owner && active.data && !(active.error instanceof ApiError && active.error.status === 404)
        && accessTokenSubject() === owner && !snapshot(owner)) saveSnapshot(owner, active.data);
    } catch (failure) { setError((failure as Error).message); }
  }, [owner, active.data, active.error]);

  useEffect(() => {
    let reconnectTimer: number | undefined;
    const update = () => refreshLocal((value) => value + 1);
    const reconnect = () => {
      setOnline(navigator.onLine);
      void sync(false);
      // The online event may precede network readiness (especially after waking a phone).
      clearTimeout(reconnectTimer);
      reconnectTimer = window.setTimeout(() => { void sync(false); }, 1000);
    };
    const visible = () => { if (document.visibilityState === "visible") reconnect(); };
    addEventListener(RECOVERY_CHANGED, update);
    addEventListener("storage", update);
    addEventListener("online", reconnect);
    addEventListener("offline", reconnect);
    addEventListener("focus", reconnect);
    document.addEventListener("visibilitychange", visible);
    void sync(false);
    const timer = window.setInterval(() => { void sync(false); }, 30_000);
    return () => {
      removeEventListener(RECOVERY_CHANGED, update);
      removeEventListener("storage", update);
      removeEventListener("online", reconnect);
      removeEventListener("offline", reconnect);
      removeEventListener("focus", reconnect);
      document.removeEventListener("visibilitychange", visible);
      clearInterval(timer);
      clearTimeout(reconnectTimer);
    };
  }, [sync]);

  const current = active.error instanceof ApiError && active.error.status === 404 ? undefined : active.data;
  const base = current && current.id !== cached?.id ? current : cached ?? current;
  const workoutPending = base ? pending.filter((op) => op.workoutId === base.id) : [];
  const workout = base ? withPending(base, workoutPending) : undefined;
  const conflicts: { workoutId: number; message: string }[] = [];
  let hasDraft = false;
  try {
    if (owner) {
      hasDraft = Boolean(base && drafts(owner, base.id).length);
      for (const workoutId of new Set(pending.map((operation) => operation.workoutId))) {
        const message = syncConflict(owner, workoutId);
        if (message !== null) conflicts.push({ workoutId, message });
      }
    }
  } catch (failure) { storageError ||= (failure as Error).message; }
  async function saveSet(workoutId: number, exerciseId: number, payload: WorkoutSetInput, operationId: string) {
    if (!owner || accessTokenSubject() !== owner) throw new Error("请先登录，再保存训练记录。");
    if (!snapshot(owner) && active.data) saveSnapshot(owner, active.data);
    enqueue(owner, workoutId, exerciseId, operationId, payload);
    void sync(false);
  }
  function finished(workoutId: number) {
    if (owner) clearSnapshot(owner, workoutId);
    client.removeQueries({ queryKey: ["previous-sets", workoutId] });
    void client.resetQueries({ queryKey: ["active-workout"] });
  }
  return { active: { ...active, data: workout }, owner, online, syncing, sync, saveSet, finished, conflicts,
    pendingCount: workoutPending.length, otherPendingCount: pending.length - workoutPending.length,
    error: storageError || error, hasDraft, canFinish: online && workoutPending.length === 0 && !hasDraft && !storageError,
    isClosed: base && base.status !== "IN_PROGRESS" };
}

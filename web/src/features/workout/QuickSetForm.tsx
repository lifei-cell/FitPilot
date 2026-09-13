import { useQuery } from "@tanstack/react-query";
import { useRef, useState, type FormEvent } from "react";
import { accessTokenSubject, api } from "../../api/client";
import type { WorkoutExercise, WorkoutSet, WorkoutSetInput } from "../../api/types";

import { readDraft, saveDraft, clearDraft, type SetDraft } from "./workoutRecoveryStore";

type Props = {
  workoutId: number;
  exercise: WorkoutExercise;
  busy: boolean;
  onSave(payload: WorkoutSetInput, idempotencyKey: string): Promise<unknown>;
};

/** Suggestions are editable drafts. Only an explicit submit records a completed set. */
export function QuickSetForm({ workoutId, exercise, busy, onSave }: Props) {
  const owner = accessTokenSubject();
  const [restoreError] = useState(() => {
    try { if (owner) readDraft(owner, workoutId, exercise.id); return ""; }
    catch (failure) { return (failure as Error).message; }
  });
  const previous = useQuery({
    queryKey: ["previous-sets", workoutId, exercise.id],
    queryFn: () => api<WorkoutSet[]>(`/workouts/${workoutId}/exercises/${exercise.id}/previous-sets`),
    retry: false,
    staleTime: Infinity,
  });
  const working = exercise.sets.filter((set) => !set.isWarmup);
  const source = previous.data?.[working.length];
  // Beyond the previous session's length, repeat the most recent current working set.
  const fallback = working.at(-1);
  const suggested = source ?? fallback;
  const [draft, setDraft] = useState<SetDraft | null>(() => {
    try { return owner ? readDraft(owner, workoutId, exercise.id) : null; } catch { return null; }
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const locked = useRef(false);
  const attempt = useRef({ body: "", key: "" });
  const values = draft ?? { weight: suggested?.weightKg == null ? "" : String(suggested.weightKg),
    reps: suggested?.reps == null ? "" : String(suggested.reps), rpe: "" };

  function edit(next: { weight: string; reps: string; rpe: string }) {
    const updated = { ...next, id: crypto.randomUUID() };
    setDraft(updated);
    try {
      if (owner) saveDraft(owner, workoutId, exercise.id, updated);
      setError("");
    } catch (failure) { setError((failure as Error).message); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locked.current || busy) return;
    locked.current = true;
    setSaving(true);
    setError("");
    const payload: WorkoutSetInput = { weightKg: Number(values.weight), reps: Number(values.reps),
      isWarmup: false, isFailure: false };
    if (values.rpe !== "") payload.rpe = Number(values.rpe);
    try {
      const body = JSON.stringify(payload);
      if (attempt.current.body !== body) attempt.current = { body, key: draft?.id ?? crypto.randomUUID() };
      if (owner) saveDraft(owner, workoutId, exercise.id, { ...values, id: attempt.current.key });
      await onSave(payload, attempt.current.key);
      if (owner) clearDraft(owner, workoutId, exercise.id, attempt.current.key);
      attempt.current = { body: "", key: "" };
      setDraft(null);
    } catch (failure) {
      setDraft({ ...values, id: attempt.current.key || draft?.id || crypto.randomUUID() });
      setError(failure instanceof Error ? failure.message : "保存失败，请重试");
    } finally {
      locked.current = false;
      setSaving(false);
    }
  }

  return <div>
    <p>{source ? `沿用上次第 ${working.length + 1} 个正式组：${source.weightKg} kg × ${source.reps} 次（待确认）`
      : fallback ? "沿用本次上一组（待确认）"
      : previous.isPending ? "正在查找上次记录，可直接手动填写。"
      : previous.isError ? "上次记录加载失败，可手动填写。" : "暂无上次正式组记录，请填写第一组。"}</p>
    <form className="inline-set-form" onSubmit={submit}>
      <input aria-label="重量 kg" name="weight" type="number" step=".01" min="0" placeholder="重量 kg" required
        value={values.weight} disabled={saving || busy}
        onChange={(e) => edit({ ...values, weight: e.target.value })} />
      <input aria-label="次数" name="reps" type="number" min="1" step="1" placeholder="次数" required
        value={values.reps} disabled={saving || busy}
        onChange={(e) => edit({ ...values, reps: e.target.value })} />
      <input aria-label="RPE" name="rpe" type="number" step=".5" min="1" max="10" placeholder="RPE"
        value={values.rpe} disabled={saving || busy}
        onChange={(e) => edit({ ...values, rpe: e.target.value })} />
      <button className="secondary-button" disabled={saving || busy}>
        {saving ? "保存中…" : suggested ? "完成一组" : "记录一组"}
      </button>
    </form>
    {owner && draft && !error && <small>填写内容已保存到本机</small>}
    {(error || restoreError) && <p role="alert" className="form-error">{error || restoreError}</p>}
  </div>;
}

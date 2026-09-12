import { useQuery } from "@tanstack/react-query";
import { useRef, useState, type FormEvent } from "react";
import { api } from "../../api/client";
import type { WorkoutExercise, WorkoutSet, WorkoutSetInput } from "../../api/types";

type Props = {
  workoutId: number;
  exercise: WorkoutExercise;
  busy: boolean;
  onSave(payload: WorkoutSetInput, idempotencyKey: string): Promise<unknown>;
};

/** Suggestions are editable drafts. Only an explicit submit records a completed set. */
export function QuickSetForm({ workoutId, exercise, busy, onSave }: Props) {
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
  const [draft, setDraft] = useState<{ weight: string; reps: string; rpe: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const locked = useRef(false);
  const attempt = useRef({ body: "", key: "" });
  const values = draft ?? { weight: suggested?.weightKg == null ? "" : String(suggested.weightKg),
    reps: suggested?.reps == null ? "" : String(suggested.reps), rpe: "" };

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
      if (attempt.current.body !== body) attempt.current = { body, key: crypto.randomUUID() };
      await onSave(payload, attempt.current.key);
      attempt.current = { body: "", key: "" };
      setDraft(null);
    } catch (failure) {
      setDraft(values);
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
        onChange={(e) => setDraft({ ...values, weight: e.target.value })} />
      <input aria-label="次数" name="reps" type="number" min="1" step="1" placeholder="次数" required
        value={values.reps} disabled={saving || busy}
        onChange={(e) => setDraft({ ...values, reps: e.target.value })} />
      <input aria-label="RPE" name="rpe" type="number" step=".5" min="1" max="10" placeholder="RPE"
        value={values.rpe} disabled={saving || busy}
        onChange={(e) => setDraft({ ...values, rpe: e.target.value })} />
      <button className="secondary-button" disabled={saving || busy}>
        {saving ? "保存中…" : suggested ? "完成一组" : "记录一组"}
      </button>
    </form>
    {error && <p role="alert" className="form-error">{error}</p>}
  </div>;
}

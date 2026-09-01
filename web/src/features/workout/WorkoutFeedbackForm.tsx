import { useState, type FormEvent } from "react";
import type { WorkoutFeedbackInput } from "../../api/types";

type Props = {
  busy: boolean;
  onSubmit(feedback: WorkoutFeedbackInput): void;
  onSkip(): void;
};

export function WorkoutFeedbackForm({ busy, onSubmit, onSkip }: Props) {
  const [fatigue, setFatigue] = useState(5);
  const [pain, setPain] = useState(0);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    onSubmit({ fatigueScore: fatigue, painScore: pain, notes: String(data.get("notes")).trim() || undefined });
  }
  return (
    <form className="workout-feedback" onSubmit={submit}>
      <div><strong>训练后反馈</strong><small>用于生成可解释的计划调整建议，可跳过。</small></div>
      <label>疲劳 {fatigue}/10<input aria-label="疲劳" type="range" min="1" max="10" value={fatigue} onChange={(e) => setFatigue(Number(e.target.value))} /></label>
      <label>疼痛 {pain}/10<input aria-label="疼痛" type="range" min="0" max="10" value={pain} onChange={(e) => setPain(Number(e.target.value))} /></label>
      <textarea name="notes" maxLength={1000} placeholder="可选：记录不适位置、恢复状态或其他感受" />
      <div className="action-row"><button type="button" className="secondary-button" disabled={busy} onClick={onSkip}>跳过并完成</button><button className="primary-button compact" disabled={busy}>提交并完成</button></div>
    </form>
  );
}

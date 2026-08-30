import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Clock3, ShieldCheck } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api } from "../../api/client";
import type {
  AgentPendingAction,
  Exercise,
  PageResult,
} from "../../api/types";
import "./pending-plan-card.css";

type Props = {
  action: AgentPendingAction;
  status: "pending" | "confirming" | "expired" | "error";
  error?: string;
  onConfirm(): void;
  onExpire(): void;
};

function remainingLabel(expiresAt: number, now: number) {
  const seconds = Math.max(0, Math.ceil((expiresAt - now) / 1000));
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${minutes}:${String(rest).padStart(2, "0")}`;
}

export function PendingPlanCard({
  action,
  status,
  error,
  onConfirm,
  onExpire,
}: Props) {
  const [now, setNow] = useState(Date.now());
  const expiresAt = new Date(action.expiresAt).getTime();
  const expired =
    status === "expired" || !Number.isFinite(expiresAt) || now >= expiresAt;
  const catalog = useQuery({
    queryKey: ["exercise-catalog"],
    queryFn: () => api<PageResult<Exercise>>("/exercises?size=100"),
    staleTime: 10 * 60_000,
  });
  const exerciseNames = useMemo(
    () =>
      new Map(
        (catalog.data?.items ?? []).map((exercise) => [
          exercise.id,
          exercise.name,
        ]),
      ),
    [catalog.data],
  );

  useEffect(() => {
    if (expired) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [expired]);

  useEffect(() => {
    if (expired && status === "pending") onExpire();
  }, [expired, onExpire, status]);

  return (
    <section className={`pending-plan-card ${expired ? "expired" : ""}`}>
      <header>
        <div>
          <span className="pending-plan-kicker">
            <ShieldCheck size={15} /> 待确认写操作
          </span>
          <h3>{action.preview.name}</h3>
          <p>
            {action.preview.goal} · {action.preview.durationWeeks} 周 · 每周{" "}
            {action.preview.days.length} 练
          </p>
        </div>
        <span className="pending-plan-expiry">
          <Clock3 size={14} />
          {expired ? "已过期" : `${remainingLabel(expiresAt, now)} 后过期`}
        </span>
      </header>

      {action.preview.description && (
        <p className="pending-plan-description">
          {action.preview.description}
        </p>
      )}

      <div className="pending-plan-days">
        {action.preview.days.map((day) => (
          <article key={day.dayNumber}>
            <strong>
              DAY {day.dayNumber} · {day.name}
            </strong>
            <ul>
              {day.exercises.map((exercise) => (
                <li key={`${day.dayNumber}-${exercise.sequence}`}>
                  <span>
                    {exerciseNames.get(exercise.exerciseId) ??
                      `动作 #${exercise.exerciseId}`}
                  </span>
                  <small>
                    {exercise.targetSets} 组 × {exercise.targetRepsMin}–
                    {exercise.targetRepsMax} 次
                    {exercise.targetRpe ? ` · RPE ${exercise.targetRpe}` : ""}
                  </small>
                </li>
              ))}
            </ul>
          </article>
        ))}
      </div>

      {action.guardrailWarnings.length > 0 && (
        <div className="pending-plan-warning">
          <AlertTriangle size={16} />
          {action.guardrailWarnings.join("；")}
        </div>
      )}
      {error && <p className="form-error pending-plan-error">{error}</p>}

      <footer>
        <p>
          {expired
            ? "确认令牌已失效，请重新让教练生成计划。"
            : "确认后仅保存为草稿，不会自动激活或覆盖当前计划。"}
        </p>
        <button
          className="primary-button compact"
          disabled={expired || status === "confirming"}
          onClick={onConfirm}
        >
          <CheckCircle2 size={17} />
          {status === "confirming" ? "确认中…" : "确认并保存草稿"}
        </button>
      </footer>
    </section>
  );
}

import { Clock3 } from "lucide-react";
import { useEffect, useState } from "react";
import "./workout-rest-timer.css";

type Props = {
  durationSeconds: number;
  endsAt: number;
  onReset(): void;
  onDismiss(): void;
};

export function WorkoutRestTimer({
  durationSeconds,
  endsAt,
  onReset,
  onDismiss,
}: Props) {
  const [sampledAt, setSampledAt] = useState(Date.now);
  const seconds = Math.min(
    durationSeconds,
    Math.max(0, Math.ceil((endsAt - sampledAt) / 1000)),
  );

  useEffect(() => {
    let timeout: number | undefined;

    function schedule(now: number) {
      const remaining = endsAt - now;
      if (remaining > 0) {
        timeout = window.setTimeout(update, remaining % 1000 || 1000);
      }
    }

    function update() {
      window.clearTimeout(timeout);
      const now = Date.now();
      setSampledAt(now);
      schedule(now);
    }

    timeout = window.setTimeout(update, 0);
    document.addEventListener("visibilitychange", update);
    window.addEventListener("pageshow", update);
    return () => {
      window.clearTimeout(timeout);
      document.removeEventListener("visibilitychange", update);
      window.removeEventListener("pageshow", update);
    };
  }, [endsAt]);

  const minutes = String(Math.floor(seconds / 60)).padStart(2, "0");
  const remainder = String(seconds % 60).padStart(2, "0");

  return (
    <div className="workout-rest-timer">
      <div className="workout-rest-time">
        <Clock3 size={16} aria-hidden="true" />
        <span>组间休息</span>
        <strong role="timer" aria-label="组间休息剩余时间">
          {minutes}:{remainder}
        </strong>
        <span role="status">{seconds === 0 ? "休息结束" : ""}</span>
      </div>
      <div className="workout-rest-actions">
        <button type="button" className="secondary-button" onClick={onReset}>
          重置休息
        </button>
        <button type="button" className="secondary-button" onClick={onDismiss}>
          {seconds === 0 ? "关闭计时" : "跳过休息"}
        </button>
      </div>
    </div>
  );
}

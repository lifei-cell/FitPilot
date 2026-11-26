import { useQuery } from "@tanstack/react-query";
import {
  ArrowUpRight,
  CalendarDays,
  Clock3,
  Dumbbell,
  Trophy,
} from "lucide-react";
import { api } from "../api/client";
import type { Workout } from "../api/types";
import { activePlanQuery } from "../features/plan/activePlanQuery";

type Overview = {
  workoutsThisWeek: number;
  trainingDurationMinutes: number;
  trainingVolume: number;
  personalRecords: number;
};
export function DashboardPage() {
  const overview = useQuery({
    queryKey: ["overview"],
    queryFn: () => api<Overview>("/analytics/overview"),
  });
  const plan = useQuery(activePlanQuery);
  const workout = useQuery({
    queryKey: ["active-workout"],
    queryFn: () => api<Workout>("/workouts/active/current"),
    retry: false,
  });
  const data = overview.data ?? {
    workoutsThisWeek: 0,
    trainingDurationMinutes: 0,
    trainingVolume: 0,
    personalRecords: 0,
  };
  return (
    <main className="page dashboard">
      <header className="page-header">
        <div>
          <p className="eyebrow">YOUR TRAINING COMMAND CENTER</p>
          <h1>今天，继续向上。</h1>
          <p className="subtle">用完成的每一组，积累下一次突破。</p>
        </div>
        <span className="date-chip">
          <CalendarDays size={16} />
          本周训练概览
        </span>
      </header>
      <section className="metric-grid">
        <Metric
          icon={<Dumbbell />}
          label="本周训练"
          value={String(data.workoutsThisWeek)}
          unit="次"
        />
        <Metric
          icon={<Clock3 />}
          label="训练时长"
          value={String(data.trainingDurationMinutes)}
          unit="分钟"
        />
        <Metric
          icon={<ArrowUpRight />}
          label="总训练量"
          value={Number(data.trainingVolume).toLocaleString()}
          unit="kg"
        />
        <Metric
          icon={<Trophy />}
          label="新个人纪录"
          value={String(data.personalRecords)}
          unit="项"
          accent
        />
      </section>
      <section className="dashboard-grid">
        <article className="hero-card">
          <p className="eyebrow">NEXT SESSION</p>
          <h2>
            {workout.data
              ? "训练正在进行"
              : (plan.data?.name ?? "训练计划")}
          </h2>
          <p>
            {workout.data
              ? `${workout.data.name} · 已加载 ${workout.data.exercises.length} 个动作`
              : plan.data
                ? `每周 ${plan.data.daysPerWeek} 练 · ${plan.data.goal}`
                : "从训练预设开始，或自定义计划。"}
          </p>
          <a
            className="primary-button inline"
            href={
              workout.data ? "/workouts" : plan.data ? "/workouts" : "/plans"
            }
          >
            {workout.data ? "继续训练" : plan.data ? "选择训练日" : "查看计划"}{" "}
            <ArrowUpRight size={18} />
          </a>
          <div className="hero-number">
            {plan.data?.daysPerWeek ?? "—"}
            <small>DAYS / WEEK</small>
          </div>
        </article>
        <article className="panel">
          <div className="panel-title">
            <div>
              <p className="eyebrow">ACTIVE PLAN</p>
              <h3>{plan.data?.name ?? "当前训练计划"}</h3>
            </div>
            <CalendarDays />
          </div>
          {plan.isPending && <p role="status">正在加载训练计划…</p>}
          {plan.isError && (
            <div role="alert">
              <p className="form-error">暂时无法加载训练计划。</p>
              <button
                className="secondary-button"
                disabled={plan.isFetching}
                onClick={() => void plan.refetch()}
              >
                重试加载
              </button>
            </div>
          )}
          {plan.data === null && !plan.isError && <p>暂无激活计划</p>}
          {plan.data && (
            <ol className="dashboard-plan-days" aria-label="计划训练日">
              {plan.data.days.map((day) => (
                <li key={day.id}>
                  <span>第 {day.dayNumber} 练</span>
                  <strong>{day.name}</strong>
                </li>
              ))}
            </ol>
          )}
        </article>
      </section>
    </main>
  );
}

function Metric({
  icon,
  label,
  value,
  unit,
  accent = false,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  unit: string;
  accent?: boolean;
}) {
  return (
    <article className={`metric-card ${accent ? "accent" : ""}`}>
      <span className="metric-icon">{icon}</span>
      <p>{label}</p>
      <strong>
        {value}
        <small>{unit}</small>
      </strong>
    </article>
  );
}

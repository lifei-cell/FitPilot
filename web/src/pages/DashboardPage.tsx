import { useQuery } from "@tanstack/react-query";
import {
  ArrowUpRight,
  CalendarDays,
  Clock3,
  Dumbbell,
  Trophy,
} from "lucide-react";
import { api } from "../api/client";

type Overview = {
  workoutsThisWeek: number;
  trainingDurationMinutes: number;
  trainingVolume: number;
  personalRecords: number;
};
type Plan = {
  id: number;
  name: string;
  goal: string;
  daysPerWeek: number;
  days: { id: number; dayNumber: number; name: string; exercises: unknown[] }[];
};
type Workout = {
  id: number;
  name: string;
  startedAt: string;
  exercises: unknown[];
};

export function DashboardPage() {
  const overview = useQuery({
    queryKey: ["overview"],
    queryFn: () => api<Overview>("/analytics/overview"),
  });
  const plan = useQuery({
    queryKey: ["active-plan"],
    queryFn: () => api<Plan>("/training-plans/active/current"),
    retry: false,
  });
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
              : (plan.data?.name ?? "创建第一份训练计划")}
          </h2>
          <p>
            {workout.data
              ? `${workout.data.name} · 已加载 ${workout.data.exercises.length} 个动作`
              : plan.data
                ? `每周 ${plan.data.daysPerWeek} 练 · ${plan.data.goal}`
                : "先建立结构，再交给执行。"}
          </p>
          <a
            className="primary-button inline"
            href={
              workout.data ? "/workouts" : plan.data ? "/workouts" : "/plans"
            }
          >
            {workout.data ? "继续训练" : "安排训练"} <ArrowUpRight size={18} />
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
              <h3>{plan.data?.name ?? "暂无激活计划"}</h3>
            </div>
            <CalendarDays />
          </div>
          <div className="week-strip">
            {["一", "二", "三", "四", "五", "六", "日"].map((day, index) => (
              <span
                className={
                  plan.data?.days.some((d) => d.dayNumber === index + 1)
                    ? "training-day"
                    : ""
                }
                key={day}
              >
                <b>{day}</b>
                <small>
                  {plan.data?.days.some((d) => d.dayNumber === index + 1)
                    ? "练"
                    : "休"}
                </small>
              </span>
            ))}
          </div>
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

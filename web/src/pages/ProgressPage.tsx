import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api } from "../api/client";
import type { Exercise, PageResult } from "../api/types";
import { PageHeader, Panel, SectionTitle } from "../components/PageParts";

type WeightPoint = {
  recordedAt: string;
  weightKg: number;
  bodyFatPercentage?: number;
  muscleMassKg?: number;
};
type ProgressPoint = { date: string; maxWeight: number; estimated1rm: number };
export function ProgressPage() {
  const [exerciseId, setExerciseId] = useState(1);
  const weight = useQuery({
    queryKey: ["weight-trend"],
    queryFn: () => api<WeightPoint[]>("/analytics/body-weight"),
  });
  const exercises = useQuery({
    queryKey: ["exercise-options"],
    queryFn: () => api<PageResult<Exercise>>("/exercises?size=30"),
  });
  const strength = useQuery({
    queryKey: ["strength-progress", exerciseId],
    queryFn: () =>
      api<ProgressPoint[]>(`/analytics/exercises/${exerciseId}/progress`),
  });
  return (
    <main className="page">
      <PageHeader
        eyebrow="PROGRESS, PROVEN"
        title="进度分析"
        description="把主观感受变成可复盘的趋势。"
      />
      <div className="chart-grid">
        <Panel>
          <SectionTitle title="体重趋势" detail="最近记录" />
          <div className="chart-box">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={weight.data ?? []}>
                <defs>
                  <linearGradient id="weight" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0" stopColor="#c9ff3d" stopOpacity={0.35} />
                    <stop offset="1" stopColor="#c9ff3d" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="#252b29" vertical={false} />
                <XAxis
                  dataKey="recordedAt"
                  tickFormatter={(v) => String(v).slice(5, 10)}
                  stroke="#69736e"
                />
                <YAxis stroke="#69736e" />
                <Tooltip
                  contentStyle={{
                    background: "#111515",
                    border: "1px solid #303633",
                  }}
                />
                <Area
                  type="monotone"
                  dataKey="weightKg"
                  stroke="#c9ff3d"
                  fill="url(#weight)"
                  strokeWidth={2}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </Panel>
        <Panel>
          <div className="section-title">
            <h2>力量趋势</h2>
            <select
              value={exerciseId}
              onChange={(e) => setExerciseId(Number(e.target.value))}
            >
              {exercises.data?.items.map((e) => (
                <option value={e.id} key={e.id}>
                  {e.name}
                </option>
              ))}
            </select>
          </div>
          <div className="chart-box">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={strength.data ?? []}>
                <CartesianGrid stroke="#252b29" vertical={false} />
                <XAxis dataKey="date" stroke="#69736e" />
                <YAxis stroke="#69736e" />
                <Tooltip
                  contentStyle={{
                    background: "#111515",
                    border: "1px solid #303633",
                  }}
                />
                <Line
                  type="monotone"
                  dataKey="estimated1rm"
                  name="预估 1RM"
                  stroke="#c9ff3d"
                  strokeWidth={2}
                  dot={{ fill: "#c9ff3d" }}
                />
                <Line
                  type="monotone"
                  dataKey="maxWeight"
                  name="最大重量"
                  stroke="#7b8781"
                  strokeWidth={2}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </Panel>
      </div>
    </main>
  );
}

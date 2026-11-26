import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../api/client";
import type { PageResult, Workout, WorkoutSummary } from "../../api/types";
import { Empty, Panel, SectionTitle, Status } from "../../components/PageParts";
import "./workout-history-panel.css";

export function WorkoutHistoryPanel() {
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const history = useQuery({
    queryKey: ["workout-history"],
    queryFn: ({ signal }) => api<PageResult<WorkoutSummary>>("/workouts?size=10", { signal }),
  });

  return (
    <Panel>
      <SectionTitle title="最近训练" detail="最近 10 次" />
      {history.isPending ? <p role="status">{history.isPaused ? "联网后加载训练记录。" : "正在加载训练记录…"}</p>
        : history.isError && !history.data ? (
          <>
            <p className="form-error" role="alert">训练记录加载失败。</p>
            <button type="button" className="secondary-button" disabled={history.isFetching}
              onClick={() => void history.refetch()}>重试加载记录</button>
          </>
        ) : history.data?.items.length ? (
          <ul className="workout-history-list" aria-label="最近训练">
            {history.data.items.map((item) => {
              const canExpand = item.status === "COMPLETED" || item.status === "CANCELLED";
              const expanded = canExpand && expandedId === item.id;
              const detailId = `workout-history-detail-${item.id}`;
              const date = new Date(item.startedAt).toLocaleDateString("zh-CN");
              return (
                <li key={item.id}>
                  <div className="workout-history-summary">
                    <div className="workout-history-name"><strong>{item.name}</strong><small>{date}</small></div>
                    <Status value={item.status} />
                    <span>{item.durationSeconds == null ? "—" : `${Math.round(item.durationSeconds / 60)} 分钟`}</span>
                    {canExpand && (
                      <button type="button" className="secondary-button" aria-expanded={expanded}
                        aria-controls={detailId} aria-label={`${expanded ? "收起" : "查看"} ${item.name}（${date}）详情`}
                        onClick={() => setExpandedId(expanded ? null : item.id)}>
                        {expanded ? "收起详情" : "查看详情"}
                      </button>
                    )}
                  </div>
                  {canExpand && <div id={detailId} hidden={!expanded}>
                    {expanded && <WorkoutHistoryDetail workoutId={item.id} />}
                  </div>}
                </li>
              );
            })}
          </ul>
        ) : <Empty title="暂无训练记录" text="开始训练后，记录会出现在这里。" />}
    </Panel>
  );
}

function WorkoutHistoryDetail({ workoutId }: { workoutId: number }) {
  const detail = useQuery({
    queryKey: ["workout-history", "detail", workoutId],
    queryFn: ({ signal }) => api<Workout>(`/workouts/${workoutId}`, { signal }),
    retry: false,
  });

  if (detail.isPending) return <p role="status">{detail.isPaused ? "联网后加载训练详情。" : "正在加载训练详情…"}</p>;
  if (detail.isError) return (
    <>
      <p className="form-error" role="alert">训练详情加载失败。</p>
      <button type="button" className="secondary-button" disabled={detail.isFetching}
        onClick={() => void detail.refetch()}>重试加载详情</button>
    </>
  );
  if (!detail.data.exercises.length) return <p>本次训练没有记录动作。</p>;

  return (
    <div className="workout-history-exercises">
      {detail.data.exercises.map((exercise) => (
        <section key={exercise.id}>
          <h3>{exercise.exerciseName}</h3>
          {exercise.sets.length ? (
            <ul>
              {exercise.sets.map((set) => (
                <li key={set.id}>
                  <span>第 {set.setNumber} 组</span>
                  <strong>{set.weightKg == null ? "重量未记录" : `${set.weightKg} kg`} × {set.reps} 次</strong>
                  {set.rpe != null && <span>RPE {set.rpe}</span>}
                  {set.rir != null && <span>RIR {set.rir}</span>}
                  {set.isWarmup && <span>热身</span>}
                  {set.isFailure && <span>力竭</span>}
                </li>
              ))}
            </ul>
          ) : <p>此动作没有组记录。</p>}
        </section>
      ))}
    </div>
  );
}

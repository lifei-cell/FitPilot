import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { CheckCircle2, CircleStop, Play, Plus } from "lucide-react";
import { api } from "../api/client";
import type {
  PageResult,
  Plan,
  Workout,
  WorkoutSetInput,
  WorkoutSummary,
  WorkoutFeedbackInput,
} from "../api/types";
import {
  Empty,
  PageHeader,
  Panel,
  SectionTitle,
  Status,
} from "../components/PageParts";
import { WorkoutSetEditor } from "../features/workout/WorkoutSetEditor";
import { WorkoutFeedbackForm } from "../features/workout/WorkoutFeedbackForm";
import { navigate } from "../components/AppShell";

export function WorkoutsPage() {
  const client = useQueryClient();
  const [error, setError] = useState("");
  const [showFeedback, setShowFeedback] = useState(false);
  const [showAdjustmentPrompt, setShowAdjustmentPrompt] = useState(false);
  const active = useQuery({
    queryKey: ["active-workout"],
    queryFn: () => api<Workout>("/workouts/active/current"),
    retry: false,
  });
  const plan = useQuery({
    queryKey: ["active-plan"],
    queryFn: () => api<Plan>("/training-plans/active/current"),
    retry: false,
    enabled: !active.data,
  });
  const history = useQuery({
    queryKey: ["workout-history"],
    queryFn: () => api<PageResult<WorkoutSummary>>("/workouts?size=10"),
  });
  const refresh = () => {
    setError("");
    void client.invalidateQueries({ queryKey: ["active-workout"] });
    void client.invalidateQueries({ queryKey: ["workout-history"] });
    void client.invalidateQueries({ queryKey: ["overview"] });
  };
  const start = useMutation({
    mutationFn: (dayId: number) =>
      api<Workout>("/workouts", {
        method: "POST",
        body: JSON.stringify({
          trainingPlanId: plan.data!.id,
          trainingPlanDayId: dayId,
        }),
      }),
    onSuccess: refresh,
    onError: (e) => setError(e.message),
  });
  const finish = useMutation({
    mutationFn: ({
      id,
      action,
      feedback,
    }: {
      id: number;
      action: "complete" | "cancel";
      feedback?: WorkoutFeedbackInput;
    }) => api(`/workouts/${id}/${action}`, { method: "POST", body: feedback ? JSON.stringify({ feedback }) : undefined }),
    onSuccess: (_, variables) => {
      setShowFeedback(false);
      if (variables.action === "complete") setShowAdjustmentPrompt(true);
      refresh();
    },
    onError: (e) => setError(e.message),
  });
  const addSet = useMutation({
    mutationFn: ({
      workoutId,
      exerciseId,
      payload,
    }: {
      workoutId: number;
      exerciseId: number;
      payload: unknown;
    }) =>
      api(`/workouts/${workoutId}/exercises/${exerciseId}/sets`, {
        method: "POST",
        body: JSON.stringify(payload),
      }),
    onSuccess: refresh,
    onError: (e) => setError(e.message),
  });
  const updateSet = useMutation({
    mutationFn: ({
      workoutId,
      setId,
      payload,
    }: {
      workoutId: number;
      setId: number;
      payload: WorkoutSetInput;
    }) =>
      api(`/workouts/${workoutId}/sets/${setId}`, {
        method: "PUT",
        body: JSON.stringify(payload),
      }),
    onSuccess: refresh,
    onError: (e) => setError(e.message),
  });
  const deleteSet = useMutation({
    mutationFn: ({ workoutId, setId }: { workoutId: number; setId: number }) =>
      api(`/workouts/${workoutId}/sets/${setId}`, { method: "DELETE" }),
    onSuccess: refresh,
    onError: (e) => setError(e.message),
  });
  function submitSet(event: FormEvent<HTMLFormElement>, exerciseId: number) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    addSet.mutate({
      workoutId: active.data!.id,
      exerciseId,
      payload: {
        weightKg: Number(data.get("weight")),
        reps: Number(data.get("reps")),
        rpe: Number(data.get("rpe")),
        isWarmup: false,
        isFailure: false,
      },
    });
    event.currentTarget.reset();
  }
  return (
    <main className="page">
      <PageHeader
        eyebrow="LIVE WORKOUT"
        title="训练执行"
        description="专注当前一组，系统负责保存上下文。"
      />
      {error && <p className="form-error error-banner">{error}</p>}
      {active.data ? (
        <Panel className="workout-live">
          <div className="live-head">
            <div>
              <Status value="IN_PROGRESS" />
              <h2>{active.data.name}</h2>
              <p>
                开始于{" "}
                {new Date(active.data.startedAt).toLocaleTimeString("zh-CN", {
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </p>
            </div>
            <div className="action-row">
              <button
                className="secondary-button danger"
                onClick={() =>
                  finish.mutate({ id: active.data!.id, action: "cancel" })
                }
              >
                <CircleStop size={16} />
                取消
              </button>
              <button
                className="primary-button compact"
                onClick={() => setShowFeedback(true)}
              >
                <CheckCircle2 size={17} />
                完成训练
              </button>
            </div>
          </div>
          {showFeedback ? <WorkoutFeedbackForm busy={finish.isPending}
            onSubmit={(feedback) => finish.mutate({ id: active.data!.id, action: "complete", feedback })}
            onSkip={() => finish.mutate({ id: active.data!.id, action: "complete" })} /> : null}
          <div className="exercise-stack">
            {active.data.exercises.map((exercise, index) => (
              <article className="exercise-live" key={exercise.id}>
                <div className="exercise-index">
                  {String(index + 1).padStart(2, "0")}
                </div>
                <div className="exercise-main">
                  <h3>{exercise.exerciseName}</h3>
                  <p>
                    目标 {exercise.targetSets ?? "—"} 组 ×{" "}
                    {exercise.targetRepsMin ?? "—"}–
                    {exercise.targetRepsMax ?? "—"} 次 · RPE{" "}
                    {exercise.targetRpe ?? "—"}
                  </p>
                  <div className="workout-set-list">
                    {exercise.sets.map((set) => (
                      <WorkoutSetEditor
                        key={set.id}
                        set={set}
                        busy={
                          (updateSet.isPending &&
                            updateSet.variables?.setId === set.id) ||
                          (deleteSet.isPending &&
                            deleteSet.variables?.setId === set.id)
                        }
                        onSave={(payload) =>
                          updateSet.mutate({
                            workoutId: active.data!.id,
                            setId: set.id,
                            payload,
                          })
                        }
                        onDelete={() =>
                          deleteSet.mutate({
                            workoutId: active.data!.id,
                            setId: set.id,
                          })
                        }
                      />
                    ))}
                  </div>
                  <form
                    className="inline-set-form"
                    onSubmit={(e) => submitSet(e, exercise.id)}
                  >
                    <input
                      name="weight"
                      type="number"
                      step=".25"
                      min="0"
                      placeholder="重量 kg"
                      required
                    />
                    <input
                      name="reps"
                      type="number"
                      min="1"
                      placeholder="次数"
                      required
                    />
                    <input
                      name="rpe"
                      type="number"
                      step=".5"
                      min="1"
                      max="10"
                      placeholder="RPE"
                    />
                    <button className="secondary-button">
                      <Plus size={15} />
                      记录一组
                    </button>
                  </form>
                </div>
              </article>
            ))}
          </div>
        </Panel>
      ) : (
        <Panel>
          <SectionTitle title="选择今天的训练日" detail={plan.data?.name} />
          {plan.data?.days.length ? (
            <div className="day-grid">
              {plan.data.days.map((day) => (
                <button
                  className="day-card"
                  key={day.id}
                  onClick={() => start.mutate(day.id)}
                >
                  <span>DAY {day.dayNumber}</span>
                  <strong>{day.name}</strong>
                  <small>{day.exercises.length} 个动作</small>
                  <Play size={20} />
                </button>
              ))}
            </div>
          ) : (
            <Empty title="没有可执行计划" text="先创建并激活一份训练计划。" />
          )}
        </Panel>
      )}
      {showAdjustmentPrompt ? <Panel className="adjustment-prompt"><strong>训练已记录</strong><p>积累至少 3 次训练和 2 份反馈后，AI Coach 可根据完成率、RPE、疲劳、容量和 PR 趋势评估计划。</p><button className="primary-button inline" onClick={() => navigate("/coach")}>前往 AI Coach 评估调整</button></Panel> : null}
      <Panel>
        <SectionTitle title="最近训练" detail="最近 10 次" />
        {history.data?.items.length ? (
          <div className="table-list">
            {history.data.items.map((item) => (
              <div key={item.id}>
                <div>
                  <strong>{item.name}</strong>
                  <small>
                    {new Date(item.startedAt).toLocaleDateString("zh-CN")}
                  </small>
                </div>
                <Status value={item.status} />
                <span>
                  {item.durationSeconds
                    ? Math.round(item.durationSeconds / 60) + " 分钟"
                    : "—"}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <Empty
            title="暂无训练记录"
            text="完成第一次训练后，历史会出现在这里。"
          />
        )}
      </Panel>
    </main>
  );
}

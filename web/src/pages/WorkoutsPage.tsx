import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { CheckCircle2, CircleStop, Play } from "lucide-react";
import { api } from "../api/client";
import type {
  Workout,
  WorkoutSetInput,
  WorkoutFeedbackInput,
} from "../api/types";
import {
  Empty,
  PageHeader,
  Panel,
  SectionTitle,
  Status,
} from "../components/PageParts";
import { useWorkoutRecovery } from "../features/workout/useWorkoutRecovery";
import { exportRecovery } from "../features/workout/workoutRecoveryStore";
import { QuickSetForm } from "../features/workout/QuickSetForm";
import { WorkoutExerciseGuide } from "../features/workout/WorkoutExerciseGuide";
import { WorkoutHistoryPanel } from "../features/workout/WorkoutHistoryPanel";
import { WorkoutSetEditor } from "../features/workout/WorkoutSetEditor";
import { WorkoutFeedbackForm } from "../features/workout/WorkoutFeedbackForm";
import { activePlanQuery } from "../features/plan/activePlanQuery";
import { navigate } from "../components/AppShell";

export function WorkoutsPage() {
  const client = useQueryClient();
  const [recording, setRecording] = useState(false);
  const [error, setError] = useState("");
  const [feedbackWorkoutId, setFeedbackWorkoutId] = useState<number | null>(null);
  const [openGuideId, setOpenGuideId] = useState<number | null>(null);
  const [showAdjustmentPrompt, setShowAdjustmentPrompt] = useState(false);
  const recovery = useWorkoutRecovery();
  const { active } = recovery;
  const plan = useQuery({
    ...activePlanQuery,
    enabled: !active.data || recovery.isClosed,
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
      recovery.finished(variables.id);
      setFeedbackWorkoutId(null);
      if (variables.action === "complete") setShowAdjustmentPrompt(true);
      refresh();
    },
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
  return (
    <main className="page">
      <PageHeader
        eyebrow="LIVE WORKOUT"
        title="训练执行"
        description="专注当前一组，系统负责保存上下文。"
      />
      {(active.data || recovery.otherPendingCount > 0) && <Panel>
        {active.data && <p role="status" aria-label="训练同步状态">{recovery.pendingCount ? `已保存到本机 · ${recovery.pendingCount} 组待同步`
          : recovery.online ? "训练记录已同步；填写中的内容自动保存到本机" : "离线模式 · 可继续记录，联网后自动同步"}</p>}
        {recovery.otherPendingCount > 0 && <p>其他训练有 {recovery.otherPendingCount} 组待同步。</p>}
        {recovery.conflicts.length > 0 && <ul role="alert" className="form-error">
          {recovery.conflicts.map(({ workoutId, message }) => (
            <li key={workoutId}>训练 #{workoutId} 同步暂停：{message}。记录仍在本机，可重试或导出。</li>
          ))}
        </ul>}
        {recovery.syncing && <p>正在同步…</p>}
        {recovery.isClosed && <p>这次训练已在服务器结束，本机待同步记录仍保留，请核对。</p>}
        {recovery.pendingCount > 0 && !recovery.isClosed && <p>当前训练的组同步成功后，即可完成或取消训练。</p>}
        {recovery.hasDraft && <p>还有填写中的一组，请先记录或清空输入，再完成训练。</p>}
        <button className="secondary-button" disabled={!recovery.online || recovery.syncing}
          onClick={() => void recovery.sync()}>重试同步</button>
        {recovery.owner && <button className="secondary-button" onClick={() => exportRecovery(recovery.owner!)}>导出本机记录</button>}
      </Panel>}
      {recovery.error && <p role="alert" className="form-error">{recovery.error}</p>}
      {error && <p className="form-error error-banner">{error}</p>}
      {active.data && !recovery.isClosed ? (
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
                disabled={recording || finish.isPending || !recovery.canFinish}
                onClick={() =>
                  finish.mutate({ id: active.data!.id, action: "cancel" })
                }
              >
                <CircleStop size={16} />
                取消
              </button>
              <button
                className="primary-button compact"
                disabled={recording || finish.isPending || !recovery.canFinish}
                onClick={() => setFeedbackWorkoutId(active.data!.id)}
              >
                <CheckCircle2 size={17} />
                完成训练
              </button>
            </div>
          </div>
          {feedbackWorkoutId === active.data.id ? <WorkoutFeedbackForm key={feedbackWorkoutId}
            busy={finish.isPending || recording || !recovery.canFinish}
            onSubmit={(feedback) => finish.mutate({ id: feedbackWorkoutId, action: "complete", feedback })}
            onSkip={() => finish.mutate({ id: feedbackWorkoutId, action: "complete" })} /> : null}
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
                  <WorkoutExerciseGuide
                    exercise={exercise}
                    expanded={openGuideId === exercise.id}
                    onToggle={() =>
                      setOpenGuideId((id) => id === exercise.id ? null : exercise.id)
                    }
                  />
                  <div className="workout-set-list">
                    {exercise.sets.map((set) => set.localOperationId ? (
                      <div className="workout-set-line" key={set.localOperationId}>
                        第 {set.setNumber} 组 · {set.weightKg} kg × {set.reps} 次 · 已保存到本机，待同步
                      </div>
                    ) : (
                      <WorkoutSetEditor
                        key={set.id}
                        set={set}
                        busy={
                          !recovery.canFinish || recording || (updateSet.isPending &&
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
                  <QuickSetForm workoutId={active.data!.id} exercise={exercise}
                    busy={recording || finish.isPending || updateSet.isPending || deleteSet.isPending}
                    onSave={async (payload, idempotencyKey) => {
                      setRecording(true);
                      try {
                        await recovery.saveSet(active.data!.id, exercise.id, payload, idempotencyKey);
                      } finally { setRecording(false); }
                    }} />
                </div>
              </article>
            ))}
          </div>
        </Panel>
      ) : (
        <Panel>
          <SectionTitle title="选择训练日" detail={plan.data?.name} />
          {plan.isError ? (
            <div role="alert">
              <p className="form-error">暂时无法加载训练计划。</p>
              <button
                className="secondary-button"
                disabled={!recovery.online || plan.isFetching}
                onClick={() => void plan.refetch()}
              >
                重试加载
              </button>
            </div>
          ) : plan.isPending ? (
            <p role="status">
              {recovery.online ? "正在加载训练计划…" : "联网后可加载训练计划。"}
            </p>
          ) : plan.data?.days.length ? (
            <div className="day-grid">
              {plan.data.days.map((day) => (
                <button
                  className="day-card"
                  disabled={!recovery.online || start.isPending}
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
            <>
              <Empty title="没有可执行计划" text="先创建并激活一份训练计划。" />
              <a className="secondary-button" href="/plans">去训练计划</a>
            </>
          )}
        </Panel>
      )}
      {showAdjustmentPrompt ? <Panel className="adjustment-prompt"><strong>训练已记录</strong><p>积累至少 3 次训练和 2 份反馈后，AI Coach 可根据完成率、RPE、疲劳、容量和 PR 趋势评估计划。</p><button className="primary-button inline" onClick={() => navigate("/coach")}>前往 AI Coach 评估调整</button></Panel> : null}
      <WorkoutHistoryPanel />
    </main>
  );
}

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarDays, Dumbbell, Pencil, X } from "lucide-react";
import { useState } from "react";
import { api } from "../../api/client";
import type { Plan, PlanCreateInput, PlanUpdateInput } from "../../api/types";
import { Panel, SectionTitle, Status } from "../../components/PageParts";
import { PlanBuilder } from "./PlanBuilder";
import { planToDraft } from "./planBuilderModel";
import "./plan-detail-panel.css";

type Props = {
  planId: number;
  onClose(): void;
  onChanged(message: string): void;
};

export function PlanDetailPanel({ planId, onClose, onChanged }: Props) {
  const client = useQueryClient();
  const [editing, setEditing] = useState(false);
  const detail = useQuery({
    queryKey: ["plan", planId],
    queryFn: () => api<Plan>(`/training-plans/${planId}`),
  });
  const update = useMutation({
    mutationFn: (payload: PlanUpdateInput) =>
      api<Plan>(`/training-plans/${planId}`, {
        method: "PUT",
        body: JSON.stringify(payload),
      }),
    onSuccess: (plan) => {
      client.setQueryData(["plan", planId], plan);
      void client.invalidateQueries({ queryKey: ["plans"] });
      void client.invalidateQueries({ queryKey: ["active-plan"] });
      setEditing(false);
      onChanged("计划修改已保存");
    },
  });

  function save(payload: PlanCreateInput) {
    if (!detail.data) return;
    update.mutate({ ...payload, version: detail.data.version });
  }

  if (detail.isPending) {
    return <Panel>正在加载计划详情…</Panel>;
  }
  if (detail.error || !detail.data) {
    return (
      <Panel>
        <p className="form-error">
          {detail.error?.message ?? "计划详情加载失败"}
        </p>
        <button className="secondary-button" onClick={onClose}>
          返回计划列表
        </button>
      </Panel>
    );
  }

  const plan = detail.data;
  if (editing) {
    return (
      <Panel>
        <SectionTitle
          title={`修改计划 · ${plan.name}`}
          detail={`版本 ${plan.version} · 保存时进行并发校验`}
        />
        <PlanBuilder
          key={`${plan.id}-${plan.version}`}
          initialDraft={planToDraft(plan)}
          submitting={update.isPending}
          serverError={update.error?.message}
          submitLabel="保存修改"
          onSubmit={save}
          onCancel={() => {
            setEditing(false);
            update.reset();
          }}
        />
      </Panel>
    );
  }

  return (
    <Panel>
      <div className="plan-detail-heading">
        <div>
          <Status value={plan.status} />
          <h2>{plan.name}</h2>
          <p>
            {plan.goal} · {plan.durationWeeks} 周 · 每周 {plan.daysPerWeek} 练
          </p>
        </div>
        <div className="plan-detail-actions">
          {plan.status === "DRAFT" && (
            <button
              className="primary-button compact"
              onClick={() => {
                update.reset();
                setEditing(true);
              }}
            >
              <Pencil size={16} />
              修改计划
            </button>
          )}
          <button className="secondary-button" onClick={onClose}>
            <X size={16} />
            关闭详情
          </button>
        </div>
      </div>

      {plan.description && (
        <p className="plan-detail-description">{plan.description}</p>
      )}
      {plan.status !== "DRAFT" && (
        <p className="plan-detail-readonly">
          当前计划已进入执行阶段，仅支持查看。训练记录使用启动时快照，不会被未来计划调整覆盖。
        </p>
      )}

      <div className="plan-detail-days">
        {plan.days.map((day) => (
          <article key={day.id}>
            <header>
              <span>
                <CalendarDays size={16} /> DAY {day.dayNumber}
              </span>
              <strong>{day.name}</strong>
            </header>
            {day.notes && <p>{day.notes}</p>}
            <div>
              {day.exercises.map((exercise) => (
                <section key={exercise.id}>
                  <Dumbbell size={16} />
                  <span>
                    <strong>
                      {exercise.exerciseName ?? `动作 #${exercise.exerciseId}`}
                    </strong>
                    <small>
                      {exercise.targetSets} 组 × {exercise.targetRepsMin}–
                      {exercise.targetRepsMax} 次
                      {exercise.targetRpe
                        ? ` · RPE ${exercise.targetRpe}`
                        : ""}
                      {exercise.restSeconds !== undefined
                        ? ` · 休息 ${exercise.restSeconds} 秒`
                        : ""}
                    </small>
                    {exercise.notes && <em>{exercise.notes}</em>}
                  </span>
                </section>
              ))}
            </div>
          </article>
        ))}
      </div>
    </Panel>
  );
}

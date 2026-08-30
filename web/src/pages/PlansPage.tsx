import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Check, Plus } from "lucide-react";
import { api } from "../api/client";
import type { PageResult, Plan, PlanSummary } from "../api/types";
import {
  Empty,
  PageHeader,
  Panel,
  SectionTitle,
  Status,
} from "../components/PageParts";

export function PlansPage() {
  const client = useQueryClient();
  const [builder, setBuilder] = useState(false);
  const [message, setMessage] = useState("");
  const plans = useQuery({
    queryKey: ["plans"],
    queryFn: () => api<PageResult<PlanSummary>>("/training-plans?size=50"),
  });
  const refresh = () => client.invalidateQueries({ queryKey: ["plans"] });
  const create = useMutation({
    mutationFn: (payload: unknown) =>
      api<Plan>("/training-plans", {
        method: "POST",
        body: JSON.stringify(payload),
      }),
    onSuccess: () => {
      setBuilder(false);
      setMessage("计划已创建");
      void refresh();
    },
  });
  const activate = useMutation({
    mutationFn: (id: number) =>
      api<Plan>(`/training-plans/${id}/activate`, { method: "POST" }),
    onSuccess: () => {
      setMessage("计划已激活");
      void refresh();
      client.invalidateQueries({ queryKey: ["active-plan"] });
    },
  });
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    create.mutate({
      name: data.get("name"),
      description: data.get("description"),
      goal: data.get("goal"),
      durationWeeks: Number(data.get("durationWeeks")),
      days: [
        {
          dayNumber: Number(data.get("dayNumber")),
          name: data.get("dayName"),
          exercises: [
            {
              exerciseId: Number(data.get("exerciseId")),
              sequence: 1,
              targetSets: Number(data.get("sets")),
              targetRepsMin: Number(data.get("repsMin")),
              targetRepsMax: Number(data.get("repsMax")),
              targetRpe: Number(data.get("rpe")),
              restSeconds: Number(data.get("rest")),
            },
          ],
        },
      ],
    });
  }
  return (
    <main className="page">
      <PageHeader
        eyebrow="PROGRAM DESIGN"
        title="训练计划"
        description="让每次训练都服务于一个明确目标。"
        action={
          <button
            className="primary-button compact"
            onClick={() => setBuilder(!builder)}
          >
            <Plus size={17} />
            新建计划
          </button>
        }
      />
      {message && (
        <div className="success-banner">
          <Check size={16} />
          {message}
        </div>
      )}
      {builder && (
        <Panel>
          <SectionTitle
            title="快速计划构建器"
            detail="先建立第 1 个训练日，之后可继续编辑"
          />
          <form className="form-grid" onSubmit={submit}>
            <label>
              计划名称
              <input
                name="name"
                required
                maxLength={100}
                placeholder="力量增长 · 12 周"
              />
            </label>
            <label>
              训练目标
              <select name="goal">
                <option value="STRENGTH">力量</option>
                <option value="MUSCLE_GAIN">增肌</option>
                <option value="FAT_LOSS">减脂</option>
                <option value="GENERAL_FITNESS">综合体能</option>
              </select>
            </label>
            <label>
              周期（周）
              <input
                name="durationWeeks"
                type="number"
                min="1"
                max="104"
                defaultValue="12"
              />
            </label>
            <label>
              训练日序号
              <input
                name="dayNumber"
                type="number"
                min="1"
                max="7"
                defaultValue="1"
              />
            </label>
            <label>
              训练日名称
              <input name="dayName" required defaultValue="上肢力量" />
            </label>
            <label>
              动作 ID
              <input
                name="exerciseId"
                type="number"
                min="1"
                required
                placeholder="从动作库获取"
              />
            </label>
            <label>
              目标组数
              <input name="sets" type="number" min="1" defaultValue="4" />
            </label>
            <label>
              次数范围
              <input
                className="half"
                name="repsMin"
                type="number"
                min="1"
                defaultValue="5"
              />
              <input
                className="half"
                name="repsMax"
                type="number"
                min="1"
                defaultValue="8"
              />
            </label>
            <label>
              目标 RPE
              <input
                name="rpe"
                type="number"
                min="1"
                max="10"
                step=".5"
                defaultValue="8"
              />
            </label>
            <label>
              组间休息（秒）
              <input name="rest" type="number" min="0" defaultValue="120" />
            </label>
            <label className="wide">
              说明
              <textarea
                name="description"
                rows={2}
                placeholder="计划目标与执行说明"
              />
            </label>
            <button className="primary-button wide" disabled={create.isPending}>
              {create.isPending ? "创建中…" : "保存为草稿"}
            </button>
            {create.error && (
              <p className="form-error wide">{create.error.message}</p>
            )}
          </form>
        </Panel>
      )}
      <Panel>
        <SectionTitle
          title="我的计划"
          detail={`${plans.data?.total ?? 0} 个`}
        />
        {plans.data?.items.length ? (
          <div className="card-list">
            {plans.data.items.map((plan) => (
              <article className="list-card" key={plan.id}>
                <div>
                  <Status value={plan.status} />
                  <h3>{plan.name}</h3>
                  <p>
                    {plan.goal} · {plan.durationWeeks} 周 · 每周{" "}
                    {plan.daysPerWeek} 练
                  </p>
                </div>
                {plan.status !== "ACTIVE" && (
                  <button
                    className="secondary-button"
                    onClick={() => activate.mutate(plan.id)}
                  >
                    激活计划
                  </button>
                )}
              </article>
            ))}
          </div>
        ) : (
          <Empty
            title="还没有训练计划"
            text="从一个目标、一周安排和首个动作开始。"
          />
        )}
      </Panel>
    </main>
  );
}

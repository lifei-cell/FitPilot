import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Eye, Plus } from "lucide-react";
import { useState } from "react";
import { api } from "../api/client";
import type {
  PageResult,
  Plan,
  PlanCreateInput,
  PlanSummary,
} from "../api/types";
import {
  Empty,
  PageHeader,
  Panel,
  SectionTitle,
  Status,
} from "../components/PageParts";
import { PlanBuilder } from "../features/plan/PlanBuilder";
import { PlanDetailPanel } from "../features/plan/PlanDetailPanel";
import { PlanPresetPicker } from "../features/plan/PlanPresetPicker";
import {
  initialPlanDraft,
  type PlanDraft,
} from "../features/plan/planBuilderModel";

export function PlansPage() {
  const client = useQueryClient();
  const [draft, setDraft] = useState<PlanDraft | null>(null);
  const [selectedPlanId, setSelectedPlanId] = useState<number | null>(null);
  const [message, setMessage] = useState("");
  const plans = useQuery({
    queryKey: ["plans"],
    queryFn: () => api<PageResult<PlanSummary>>("/training-plans?size=50"),
  });
  const refresh = () => client.invalidateQueries({ queryKey: ["plans"] });
  const create = useMutation({
    mutationFn: (payload: PlanCreateInput) =>
      api<Plan>("/training-plans", {
        method: "POST",
        body: JSON.stringify(payload),
      }),
    onSuccess: () => {
      setDraft(null);
      setMessage("计划已创建并保存为草稿");
      void refresh();
    },
  });
  const activate = useMutation({
    mutationFn: (id: number) =>
      api<Plan>(`/training-plans/${id}/activate`, { method: "POST" }),
    onSuccess: (plan) => {
      client.setQueryData(["plan", plan.id], plan);
      setMessage("计划已激活");
      void refresh();
      void client.invalidateQueries({ queryKey: ["active-plan"] });
    },
  });

  function openBuilder(initialDraft: PlanDraft | null) {
    setDraft(initialDraft);
    setSelectedPlanId(null);
    setMessage("");
    create.reset();
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
            disabled={create.isPending}
            onClick={() => openBuilder(draft ? null : initialPlanDraft())}
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
      {draft && (
        <Panel>
          <SectionTitle
            title="计划构建器"
            detail="最多 7 个训练日，每日可配置多个动作"
          />
          <PlanBuilder
            initialDraft={draft}
            submitting={create.isPending}
            serverError={create.error?.message}
            onSubmit={(payload) => create.mutate(payload)}
            onCancel={() => {
              setDraft(null);
              create.reset();
            }}
          />
        </Panel>
      )}
      {selectedPlanId !== null && (
        <PlanDetailPanel
          planId={selectedPlanId}
          onClose={() => setSelectedPlanId(null)}
          onChanged={setMessage}
        />
      )}
      {!draft && selectedPlanId === null && (
        <PlanPresetPicker onSelect={openBuilder} />
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
                <div className="plan-list-actions">
                  <button
                    className="secondary-button"
                    disabled={create.isPending}
                    onClick={() => {
                      setDraft(null);
                      setMessage("");
                      setSelectedPlanId(plan.id);
                    }}
                  >
                    <Eye size={16} />
                    查看
                  </button>
                  {plan.status !== "ACTIVE" && (
                    <button
                      className="secondary-button"
                      disabled={activate.isPending}
                      onClick={() => activate.mutate(plan.id)}
                    >
                      激活计划
                    </button>
                  )}
                </div>
              </article>
            ))}
          </div>
        ) : (
          <Empty
            title="还没有训练计划"
            text="套用预设，或新建自己的训练计划。"
          />
        )}
        {activate.error && (
          <p className="form-error">{activate.error.message}</p>
        )}
      </Panel>
    </main>
  );
}

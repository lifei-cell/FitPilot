import type { PlanAdjustment } from "../../api/types";

export function AdjustmentInsightCard({ adjustment, onReject }: { adjustment: PlanAdjustment; onReject(): void }) {
  const evidence = adjustment.evidence;
  return (
    <section className="adjustment-insight">
      <div><strong>计划调整依据 · {adjustment.rule}</strong><span>{adjustment.status}</span></div>
      <p>{adjustment.reasons.join("；")}</p>
      <dl>
        <div><dt>完成率</dt><dd>{Math.round(evidence.planCompletionRate * 100)}%</dd></div>
        <div><dt>平均 RPE</dt><dd>{evidence.averageRpe || "—"}</dd></div>
        <div><dt>平均疲劳</dt><dd>{evidence.averageFatigue || "—"}</dd></div>
        <div><dt>最近疼痛</dt><dd>{evidence.latestPain}</dd></div>
      </dl>
      {adjustment.status === "AWAITING_CONFIRMATION" ? <button className="text-button" onClick={onReject}>拒绝本次调整</button> : null}
    </section>
  );
}

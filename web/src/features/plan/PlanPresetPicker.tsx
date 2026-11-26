import { useQuery } from "@tanstack/react-query";
import { ArrowRight } from "lucide-react";
import { Panel, SectionTitle } from "../../components/PageParts";
import type { PlanDraft } from "./planBuilderModel";
import {
  hasPresetExercises,
  loadPresetExercises,
  planPresets,
  presetToDraft,
} from "./planPresets";
import "./plan-preset-picker.css";

export function PlanPresetPicker({
  onSelect,
}: {
  onSelect(draft: PlanDraft): void;
}) {
  const catalog = useQuery({
    queryKey: ["plan-preset-exercises"],
    queryFn: ({ signal }) => loadPresetExercises(signal),
    staleTime: 60_000,
  });

  return (
    <Panel>
      <SectionTitle title="训练预设" detail="套用后可编辑" />
      {catalog.isError && (
        <div className="plan-preset-error" role="alert">
          <span>暂时无法加载预设动作。</span>
          <button
            type="button"
            className="secondary-button"
            disabled={catalog.isFetching}
            onClick={() => void catalog.refetch()}
          >
            重试
          </button>
        </div>
      )}
      {catalog.isPending && <p role="status">正在加载预设动作…</p>}
      <div className="plan-preset-grid">
        {planPresets.map((preset) => {
          const exercises = catalog.data;
          const available = exercises && hasPresetExercises(preset, exercises);
          return (
            <article className="plan-preset-card" key={preset.name}>
              <p className="plan-preset-equipment">{preset.equipment}</p>
              <h3>{preset.name}</h3>
              <p className="plan-preset-schedule">
                每周 {preset.days.length} 练 · {preset.durationWeeks} 周
              </p>
              <p className="plan-preset-description">{preset.description}</p>
              <button
                type="button"
                className="secondary-button"
                aria-label={available ? `套用预设：${preset.name}` : undefined}
                disabled={!available || catalog.isError}
                onClick={() => {
                  if (available) onSelect(presetToDraft(preset, exercises));
                }}
              >
                {exercises && !available ? "动作暂不可用" : "套用预设"}
                <ArrowRight size={15} aria-hidden="true" />
              </button>
            </article>
          );
        })}
      </div>
    </Panel>
  );
}

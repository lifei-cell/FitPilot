import { Check, Pencil, Trash2, X } from "lucide-react";
import { useState, type FormEvent } from "react";
import type { WorkoutSet, WorkoutSetInput } from "../../api/types";
import "./workout-set-editor.css";

type Props = {
  set: WorkoutSet;
  busy: boolean;
  onSave(payload: WorkoutSetInput): void;
  onDelete(): void;
};

function optionalNumber(value: FormDataEntryValue | null) {
  const text = String(value ?? "").trim();
  return text ? Number(text) : undefined;
}

export function WorkoutSetEditor({ set, busy, onSave, onDelete }: Props) {
  const [editing, setEditing] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    onSave({
      weightKg: Number(data.get("weightKg")),
      reps: Number(data.get("reps")),
      rpe: optionalNumber(data.get("rpe")),
      rir: optionalNumber(data.get("rir")),
      isWarmup: data.get("isWarmup") === "on",
      isFailure: data.get("isFailure") === "on",
    });
    setEditing(false);
  }

  if (editing) {
    return (
      <form className="workout-set-edit" onSubmit={submit}>
        <b>#{set.setNumber}</b>
        <label>
          kg
          <input
            name="weightKg"
            type="number"
            step=".25"
            min="0"
            defaultValue={set.weightKg}
            required
          />
        </label>
        <label>
          次数
          <input
            name="reps"
            type="number"
            min="1"
            defaultValue={set.reps}
            required
          />
        </label>
        <label>
          RPE
          <input
            name="rpe"
            type="number"
            step=".5"
            min="1"
            max="10"
            defaultValue={set.rpe ?? ""}
          />
        </label>
        <label>
          RIR
          <input
            name="rir"
            type="number"
            step=".5"
            min="0"
            max="10"
            defaultValue={set.rir ?? ""}
          />
        </label>
        <label className="set-check">
          <input name="isWarmup" type="checkbox" defaultChecked={set.isWarmup} />
          热身
        </label>
        <label className="set-check">
          <input name="isFailure" type="checkbox" defaultChecked={set.isFailure} />
          力竭
        </label>
        <button className="set-icon-button save" disabled={busy} aria-label="保存组记录">
          <Check size={16} />
        </button>
        <button
          className="set-icon-button"
          type="button"
          disabled={busy}
          aria-label="取消编辑"
          onClick={() => setEditing(false)}
        >
          <X size={16} />
        </button>
      </form>
    );
  }

  return (
    <div className="workout-set-line">
      <b>#{set.setNumber}</b>
      <span>
        <strong>{set.weightKg} kg</strong> × {set.reps}
      </span>
      <small>
        {set.rpe ? `RPE ${set.rpe}` : "未记录 RPE"}
        {set.rir !== undefined ? ` · RIR ${set.rir}` : ""}
      </small>
      <div className="set-tags">
        {set.isWarmup && <i>热身</i>}
        {set.isFailure && <i>力竭</i>}
      </div>
      <div className="set-actions">
        <button
          className="set-icon-button"
          disabled={busy}
          aria-label={`编辑第 ${set.setNumber} 组`}
          onClick={() => {
            setConfirmingDelete(false);
            setEditing(true);
          }}
        >
          <Pencil size={15} />
        </button>
        {confirmingDelete ? (
          <>
            <button
              className="set-delete-confirm"
              disabled={busy}
              onClick={onDelete}
            >
              确认删除
            </button>
            <button
              className="set-icon-button"
              disabled={busy}
              aria-label="取消删除"
              onClick={() => setConfirmingDelete(false)}
            >
              <X size={15} />
            </button>
          </>
        ) : (
          <button
            className="set-icon-button danger"
            disabled={busy}
            aria-label={`删除第 ${set.setNumber} 组`}
            onClick={() => setConfirmingDelete(true)}
          >
            <Trash2 size={15} />
          </button>
        )}
      </div>
    </div>
  );
}

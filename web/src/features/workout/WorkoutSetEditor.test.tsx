import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { WorkoutSet } from "../../api/types";
import { WorkoutSetEditor } from "./WorkoutSetEditor";

const set: WorkoutSet = {
  id: 1,
  setNumber: 1,
  weightKg: 80,
  reps: 5,
  rpe: 8,
  rir: 2,
  isWarmup: true,
  isFailure: false,
};

describe("WorkoutSetEditor", () => {
  it("edits all set fields and submits normalized values", async () => {
    const onSave = vi.fn();
    const user = userEvent.setup();
    render(<WorkoutSetEditor set={set} busy={false} onSave={onSave} onDelete={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "编辑第 1 组" }));
    const weight = screen.getByRole("spinbutton", { name: "kg" });
    await user.clear(weight);
    await user.type(weight, "82.5");
    await user.clear(screen.getByRole("spinbutton", { name: "RIR" }));
    await user.click(screen.getByRole("checkbox", { name: "力竭" }));
    await user.click(screen.getByRole("button", { name: "保存组记录" }));

    expect(onSave).toHaveBeenCalledWith({
      weightKg: 82.5, reps: 5, rpe: 8, rir: undefined,
      isWarmup: true, isFailure: true,
    });
  });

  it("requires a second click before deleting and supports cancellation", async () => {
    const onDelete = vi.fn();
    const user = userEvent.setup();
    render(<WorkoutSetEditor set={set} busy={false} onSave={vi.fn()} onDelete={onDelete} />);

    await user.click(screen.getByRole("button", { name: "删除第 1 组" }));
    await user.click(screen.getByRole("button", { name: "取消删除" }));
    expect(onDelete).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "删除第 1 组" }));
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    expect(onDelete).toHaveBeenCalledOnce();

    await user.click(screen.getByRole("button", { name: "编辑第 1 组" }));
    await user.click(screen.getByRole("button", { name: "取消编辑" }));
    expect(screen.getByRole("button", { name: "编辑第 1 组" })).toBeInTheDocument();
  });
});

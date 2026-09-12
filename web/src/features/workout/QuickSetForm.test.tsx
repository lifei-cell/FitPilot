import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { renderWithQueryClient } from "../../test/render";
import { server } from "../../test/server";
import { QuickSetForm } from "./QuickSetForm";
import type { WorkoutExercise, WorkoutSet, WorkoutSetInput } from "../../api/types";

const exercise: WorkoutExercise = { id: 71, exerciseId: 1, exerciseName: "卧推", sequence: 1, sets: [] };
const previous: WorkoutSet = { id: 5, setNumber: 1, weightKg: 60, reps: 10,
  rpe: 9, isWarmup: false, isFailure: true };
function mockHistory(sets: WorkoutSet[]) {
  server.use(http.get("/api/v1/workouts/7/exercises/71/previous-sets", () =>
    HttpResponse.json({ code: 0, data: sets })));
}

describe("QuickSetForm", () => {
  it("prefills without writing and advances by working group, excluding warmups", async () => {
    mockHistory([previous, { ...previous, id: 6, setNumber: 2, reps: 8 }]);
    const save = vi.fn().mockResolvedValue(undefined);
    const { rerender } = renderWithQueryClient(<QuickSetForm workoutId={7} exercise={exercise} busy={false} onSave={save} />);
    await screen.findByText(/沿用上次第 1/);
    expect(screen.getByLabelText("重量 kg")).toHaveValue(60);
    expect(save).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "完成一组" }));
    expect(save).toHaveBeenCalledWith({ weightKg: 60, reps: 10, isWarmup: false, isFailure: false }, expect.any(String));
    rerender(<QuickSetForm workoutId={7} exercise={{ ...exercise, sets: [
      { ...previous, isWarmup: true }, previous,
    ] }} busy={false} onSave={save} />);
    expect(screen.getByLabelText("次数")).toHaveValue(8);
    expect(screen.getByLabelText("RPE")).toHaveValue(null);
  });

  it("keeps edits and reuses the idempotency key after failure", async () => {
    mockHistory([previous]);
    const save = vi.fn().mockRejectedValueOnce(new Error("保存失败")).mockResolvedValue(undefined);
    renderWithQueryClient(<QuickSetForm workoutId={7} exercise={exercise} busy={false} onSave={save} />);
    await screen.findByText(/沿用上次第 1/);
    const user = userEvent.setup();
    await user.clear(screen.getByLabelText("重量 kg"));
    await user.type(screen.getByLabelText("重量 kg"), "62.5");
    await user.click(screen.getByRole("button", { name: "完成一组" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("保存失败");
    expect(screen.getByLabelText("重量 kg")).toHaveValue(62.5);
    await user.click(screen.getByRole("button", { name: "完成一组" }));
    expect(save.mock.calls[0]).toEqual(save.mock.calls[1]);
  });

  it("supports manual zero-weight sets without manufacturing RPE and blocks duplicate clicks", async () => {
    mockHistory([]);
    let resolve!: () => void;
    const save = vi.fn<(payload: WorkoutSetInput, key: string) => Promise<void>>(() => new Promise<void>((done) => { resolve = done; }));
    renderWithQueryClient(<QuickSetForm workoutId={7} exercise={exercise} busy={false} onSave={save} />);
    await screen.findByText(/暂无上次/);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("重量 kg"), "0");
    await user.type(screen.getByLabelText("次数"), "12");
    await user.dblClick(screen.getByRole("button", { name: "记录一组" }));
    expect(save).toHaveBeenCalledTimes(1);
    expect(save.mock.calls[0][0]).toEqual({ weightKg: 0, reps: 12, isWarmup: false, isFailure: false });
    resolve();
    await waitFor(() => expect(screen.getByRole("button", { name: "记录一组" })).toBeEnabled());
  });
});

import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { Plan, Workout, WorkoutSetInput } from "../api/types";
import { renderWithQueryClient } from "../test/render";
import { server } from "../test/server";
import { WorkoutsPage } from "./WorkoutsPage";

const plan: Plan = {
  id: 1,
  name: "力量计划",
  goal: "STRENGTH",
  durationWeeks: 8,
  daysPerWeek: 1,
  status: "ACTIVE",
  version: 1,
  days: [{
    id: 11,
    dayNumber: 1,
    name: "上肢力量",
    exercises: [{ id: 21, exerciseId: 101, exerciseName: "杠铃卧推", sequence: 1,
      targetSets: 4, targetRepsMin: 5, targetRepsMax: 8, targetRpe: 8 }],
  }],
};

describe("WorkoutsPage", () => {
  it("starts a planned workout, adds a set, and completes it with feedback", async () => {
    let workout: Workout | null = null;
    let startedWith: unknown;
    let addedSet: WorkoutSetInput | undefined;
    let completedWith: unknown;
    server.use(
      http.get("/api/v1/workouts/active/current", () => workout
        ? HttpResponse.json({ code: 0, message: "success", data: workout })
        : HttpResponse.json({ code: 30002, message: "no active workout", data: null }, { status: 404 })),
      http.get("/api/v1/training-plans/active/current", () =>
        HttpResponse.json({ code: 0, message: "success", data: plan })),
      http.get("/api/v1/workouts", () =>
        HttpResponse.json({ code: 0, message: "success", data: { items: [], total: 0, page: 1, size: 10, pages: 0 } })),
      http.post("/api/v1/workouts", async ({ request }) => {
        startedWith = await request.json();
        workout = { id: 7, name: "上肢力量", status: "IN_PROGRESS", startedAt: "2026-09-02T08:00:00Z",
          exercises: [{ id: 71, exerciseId: 101, exerciseName: "杠铃卧推", sequence: 1,
            targetSets: 4, targetRepsMin: 5, targetRepsMax: 8, targetRpe: 8, sets: [] }] };
        return HttpResponse.json({ code: 0, message: "success", data: workout });
      }),
      http.post("/api/v1/workouts/7/exercises/71/sets", async ({ request }) => {
        addedSet = await request.json() as WorkoutSetInput;
        workout!.exercises[0].sets = [{ id: 701, setNumber: 1, ...addedSet }];
        return HttpResponse.json({ code: 0, message: "success", data: workout!.exercises[0].sets[0] });
      }),
      http.post("/api/v1/workouts/7/complete", async ({ request }) => {
        completedWith = await request.json();
        workout = null;
        return HttpResponse.json({ code: 0, message: "success", data: { workoutId: 7 } });
      }),
    );
    const user = userEvent.setup();
    renderWithQueryClient(<WorkoutsPage />);

    await user.click(await screen.findByRole("button", { name: /上肢力量/ }));
    expect(startedWith).toEqual({ trainingPlanId: 1, trainingPlanDayId: 11 });
    expect(await screen.findByRole("heading", { name: "上肢力量" })).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText("重量 kg"), "80");
    await user.type(screen.getByPlaceholderText("次数"), "5");
    await user.type(screen.getByPlaceholderText("RPE"), "8");
    await user.click(screen.getByRole("button", { name: "记录一组" }));
    await waitFor(() => expect(addedSet).toEqual({
      weightKg: 80, reps: 5, rpe: 8, isWarmup: false, isFailure: false,
    }));
    expect(await screen.findByText("80 kg")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "完成训练" }));
    await user.click(screen.getByRole("button", { name: "提交并完成" }));
    await waitFor(() => expect(completedWith).toEqual({
      feedback: { fatigueScore: 5, painScore: 0 },
    }));
    expect(await screen.findByText("训练已记录")).toBeInTheDocument();
  });
});

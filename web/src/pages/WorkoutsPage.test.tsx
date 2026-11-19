import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { Plan, Workout, WorkoutSetInput } from "../api/types";
import { renderWithQueryClient } from "../test/render";
import { server } from "../test/server";
import { WorkoutsPage } from "./WorkoutsPage";
import { enqueue, operations, saveDraft, saveSnapshot, snapshot } from "../features/workout/workoutRecoveryStore";

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
  it("persists a cached workout and removes stale UI when another device ends it", async () => {
    sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature");
    let ended = false;
    const cached: Workout = { id: 19, name: "缓存训练", status: "IN_PROGRESS",
      startedAt: "2026-09-12T08:00:00Z", exercises: [] };
    server.use(
      http.get("/api/v1/workouts/active/current", () => ended
        ? HttpResponse.json({ code: 30002, message: "no active workout" }, { status: 404 })
        : HttpResponse.json({ code: 0, data: cached })),
      http.get("/api/v1/workouts", () => HttpResponse.json({ code: 0, data: { items: [] } })),
      http.get("/api/v1/training-plans/active/current", () => HttpResponse.json({ code: 0, data: plan })),
    );
    const view = renderWithQueryClient(<div />);
    view.client.setQueryData(["active-workout"], cached);
    view.rerender(<WorkoutsPage />);
    await waitFor(() => expect(snapshot("99")?.id).toBe(19));
    ended = true;
    await view.client.invalidateQueries({ queryKey: ["active-workout"] });
    await waitFor(() => expect(screen.queryByRole("heading", { name: "缓存训练" })).not.toBeInTheDocument());
    expect(snapshot("99")).toBeNull();
  });

  it("starts a planned workout, adds a set, and completes it with feedback", async () => {
    sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature");
    let workout: Workout | null = null;
    let startedWith: unknown;
    let addedSet: WorkoutSetInput | undefined;
    let completedWith: unknown;
    server.use(
      http.get("/api/v1/workouts/7", () => HttpResponse.json({ code: 0, data: workout })),
      http.get("/api/v1/workouts/:id/exercises/:exerciseId/previous-sets", () =>
        HttpResponse.json({ code: 0, message: "success", data: [] })),
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

  it("syncs and completes the active workout while retaining another workout's conflict", async () => {
    sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature");
    const previousId = "00000000-0000-4000-8000-000000000001";
    const currentId = "00000000-0000-4000-8000-000000000002";
    const payload = { weightKg: 60, reps: 10, isWarmup: false, isFailure: false };
    const previous: Workout = { id: 6, name: "旧训练", status: "IN_PROGRESS",
      startedAt: "2026-09-12T08:00:00Z", exercises: [] };
    let current: Workout | null = { ...previous, id: 7, name: "当前训练",
      exercises: [{ id: 71, exerciseId: 1, exerciseName: "卧推", sequence: 1, sets: [] }] };
    saveSnapshot("99", previous);
    enqueue("99", 6, 61, previousId, payload);
    saveDraft("99", 6, 61, { id: crypto.randomUUID(), weight: "55", reps: "", rpe: "" });
    enqueue("99", 7, 71, currentId, payload);

    const sent: string[] = [];
    server.use(
      http.get("/api/v1/workouts/active/current", () => current
        ? HttpResponse.json({ code: 0, data: current })
        : HttpResponse.json({ code: 40001, message: "no active workout" }, { status: 404 })),
      http.get("/api/v1/workouts/7", () => HttpResponse.json({ code: 0, data: current })),
      http.get("/api/v1/workouts/7/exercises/71/previous-sets", () => HttpResponse.json({ code: 0, data: [] })),
      http.get("/api/v1/workouts", () => HttpResponse.json({ code: 0, data: { items: [] } })),
      http.get("/api/v1/training-plans/active/current", () => HttpResponse.json({ code: 0, data: plan })),
      http.post("/api/v1/workouts/6/exercises/61/sets", ({ request }) => {
        sent.push(request.headers.get("Client-Operation-Id")!);
        return HttpResponse.json({ code: 40002, message: "训练已结束" }, { status: 409 });
      }),
      http.post("/api/v1/workouts/7/exercises/71/sets", ({ request }) => {
        sent.push(request.headers.get("Client-Operation-Id")!);
        const set = { ...payload, id: 701, setNumber: 1, clientOperationId: currentId };
        current!.exercises[0].sets = [set];
        return HttpResponse.json({ code: 0, data: set });
      }),
      http.post("/api/v1/workouts/7/complete", () => {
        current = null;
        return HttpResponse.json({ code: 0, data: { workoutId: 7 } });
      }),
    );
    const user = userEvent.setup();
    renderWithQueryClient(<WorkoutsPage />);

    expect(await screen.findByRole("heading", { name: "当前训练" })).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("训练 #6 同步暂停");
    const complete = screen.getByRole("button", { name: "完成训练" });
    await waitFor(() => expect(complete).toBeEnabled());
    expect(sent).toEqual([previousId, currentId]);
    expect(operations("99").map(({ id }) => id)).toEqual([previousId]);

    await user.click(complete);
    await user.click(screen.getByRole("button", { name: "提交并完成" }));
    expect(await screen.findByText("训练已记录")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "导出本机记录" })).toBeInTheDocument();
    expect(operations("99").map(({ id }) => id)).toEqual([previousId]);
  });
});

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
  it("distinguishes plan lookup failure from having no active plan", async () => {
    let requests = 0;
    server.use(
      http.get("/api/v1/workouts/active/current", () =>
        HttpResponse.json({ code: 40001, message: "no active workout" }, { status: 404 })),
      http.get("/api/v1/workouts", () => HttpResponse.json({ code: 0, data: { items: [] } })),
      http.get("/api/v1/training-plans/active/current", () => {
        requests += 1;
        if (requests === 1) {
          return HttpResponse.json({ message: "unavailable" }, { status: 503 });
        }
        return HttpResponse.json({ code: 30001, message: "training plan not found" }, { status: 404 });
      }),
    );
    const user = userEvent.setup();
    renderWithQueryClient(<WorkoutsPage />);

    expect(await screen.findByRole("alert")).toHaveTextContent("暂时无法加载训练计划");
    expect(screen.queryByText("没有可执行计划")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试加载" }));
    expect(await screen.findByText("没有可执行计划")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "去训练计划" })).toHaveAttribute("href", "/plans");
  });

  it("loads read-only history on demand and retries the selected workout", async () => {
    const first = { id: 9, name: "上肢训练", status: "COMPLETED", startedAt: "2026-09-12T08:00:00Z", durationSeconds: 1800 };
    const second = { ...first, id: 8, name: "下肢训练", status: "CANCELLED" };
    const active = { ...first, id: 10, name: "今天的训练", status: "IN_PROGRESS", exercises: [] };
    const requested: string[] = [];
    server.use(
      http.get("/api/v1/workouts/active/current", () => HttpResponse.json({ code: 0, data: active })),
      http.get("/api/v1/workouts", () => HttpResponse.json({ code: 0, data: { items: [active, first, second] } })),
      http.get("/api/v1/workouts/:id", ({ params }) => {
        const id = String(params.id);
        requested.push(id);
        if (requested.length === 1) return HttpResponse.json({ code: 503, message: "unavailable" }, { status: 503 });
        if (id === "8") return HttpResponse.json({ code: 0, data: { ...second, exercises: [] } });
        return HttpResponse.json({ code: 0, data: { ...first, exercises: [
          { id: 91, exerciseName: "卧推快照", sets: [
            { id: 911, setNumber: 1, weightKg: 0, reps: 12, rpe: null, rir: null, isWarmup: true, isFailure: false },
            { id: 912, setNumber: 2, weightKg: 62.5, reps: 5, rpe: 9.5, rir: 0, isWarmup: false, isFailure: true },
          ] },
          { id: 92, exerciseName: "划船快照", sets: [] },
        ] } });
      }),
    );
    const user = userEvent.setup();
    renderWithQueryClient(<WorkoutsPage />);

    const openFirst = await screen.findByRole("button", { name: /查看 上肢训练/ });
    expect(openFirst).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("button", { name: /查看 今天的训练/ })).not.toBeInTheDocument();
    expect(requested).toEqual([]);

    await user.click(openFirst);
    expect(await screen.findByRole("alert")).toHaveTextContent("训练详情加载失败");
    await user.click(screen.getByRole("button", { name: "重试加载详情" }));
    expect(await screen.findByRole("heading", { name: "卧推快照" })).toBeInTheDocument();
    expect(screen.getByText("0 kg × 12 次")).toBeInTheDocument();
    expect(screen.getByText("62.5 kg × 5 次")).toBeInTheDocument();
    expect(screen.getByText("RIR 0")).toBeInTheDocument();
    expect(screen.getByText("热身")).toBeInTheDocument();
    expect(screen.getByText("力竭")).toBeInTheDocument();
    expect(screen.getByText("此动作没有组记录。")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /编辑第|删除第/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /查看 下肢训练/ }));
    expect(await screen.findByText("本次训练没有记录动作。")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "卧推快照" })).not.toBeInTheDocument();
    expect(requested).toEqual(["9", "9", "8"]);
    await user.click(screen.getByRole("button", { name: /收起 下肢训练/ }));
    expect(screen.queryByText("本次训练没有记录动作。")).not.toBeInTheDocument();
  });

  it("opens one on-demand exercise guide without disturbing the set form", async () => {
    const workout: Workout = {
      id: 7,
      name: "上肢练习",
      status: "IN_PROGRESS",
      startedAt: "2026-11-23T08:00:00Z",
      exercises: [
        { id: 71, exerciseId: 101, exerciseName: "杠铃卧推", sequence: 1, sets: [] },
        { id: 72, exerciseId: 202, exerciseName: "自定义划船", sequence: 2, sets: [] },
      ],
    };
    const requested: string[] = [];
    server.use(
      http.get("/api/v1/workouts/active/current", () => HttpResponse.json({ code: 0, data: workout })),
      http.get("/api/v1/workouts", () => HttpResponse.json({ code: 0, data: { items: [] } })),
      http.get("/api/v1/training-plans/active/current", () => HttpResponse.json({ code: 0, data: plan })),
      http.get("/api/v1/workouts/7/exercises/:id/previous-sets", () =>
        HttpResponse.json({ code: 0, data: [] })),
      http.get("/api/v1/exercises/:id", ({ params }) => {
        const id = String(params.id);
        requested.push(id);
        if (id === "101") {
          return HttpResponse.json({ code: 0, data: {
            id: 101, name: "杠铃卧推", englishName: "Barbell Bench Press", instructions: "双脚踩稳。",
          } });
        }
        if (requested.filter((item) => item === "202").length === 1) {
          return HttpResponse.json({ message: "unavailable" }, { status: 503 });
        }
        return HttpResponse.json({ code: 0, data: {
          id: 202, name: "自定义划船", instructions: "保持躯干稳定。",
        } });
      }),
    );
    const user = userEvent.setup();
    renderWithQueryClient(<WorkoutsPage />);

    const benchGuide = await screen.findByRole("button", { name: "查看动作教学：杠铃卧推" });
    const weight = screen.getAllByPlaceholderText("重量 kg")[0];
    await user.type(weight, "42");
    expect(requested).toEqual([]);

    await user.click(benchGuide);
    expect(await screen.findByRole("img", { name: "杠铃卧推动作示意图" })).toHaveAttribute(
      "src", "/exercise-media/repdb/bench-press.webp",
    );
    expect(screen.getByText("双脚踩稳。", { exact: false })).toBeInTheDocument();
    expect(screen.queryByTitle("杠铃卧推教学视频")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "观看教学视频" }));
    expect(screen.getByTitle("杠铃卧推教学视频")).toHaveAttribute(
      "src", expect.stringContaining("bvid=BV1JF411g7ua"),
    );

    await user.click(screen.getByRole("button", { name: "查看动作教学：自定义划船" }));
    expect(benchGuide).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByTitle("杠铃卧推教学视频")).not.toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("暂时无法加载动作教学");
    await user.click(screen.getByRole("button", { name: "重试加载教学" }));
    expect(await screen.findByText("保持躯干稳定。", { exact: false })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "观看教学视频" })).not.toBeInTheDocument();
    expect(requested).toEqual(["101", "202", "202"]);
    expect(weight).toHaveValue(42);
  });

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

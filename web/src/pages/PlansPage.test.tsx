import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { Exercise, Plan, PlanCreateInput } from "../api/types";
import { renderWithQueryClient } from "../test/render";
import { server } from "../test/server";
import { PlansPage } from "./PlansPage";

const plan: Plan = {
  id: 42, name: "基础力量", goal: "STRENGTH", durationWeeks: 8,
  daysPerWeek: 1, status: "DRAFT", version: 1,
  days: [{ id: 11, dayNumber: 1, name: "上肢", exercises: [] }],
};

function exercisePage(items: Exercise[], page = 1, pages = 1) {
  return HttpResponse.json({
    code: 0,
    message: "success",
    data: { items, total: items.length, page, size: 100, pages },
  });
}

describe("PlansPage", () => {
  it("lists, opens, and activates a draft plan", async () => {
    let active = false;
    server.use(
      http.get("/api/v1/exercises", () => exercisePage([])),
      http.get("/api/v1/training-plans", () => HttpResponse.json({ code: 0, message: "success", data: {
        items: [{ ...plan, status: active ? "ACTIVE" : "DRAFT" }], total: 1, page: 1, size: 50, pages: 1,
      } })),
      http.get("/api/v1/training-plans/42", () =>
        HttpResponse.json({ code: 0, message: "success", data: { ...plan, status: active ? "ACTIVE" : "DRAFT" } })),
      http.post("/api/v1/training-plans/42/activate", () => {
        active = true;
        return HttpResponse.json({ code: 0, message: "success", data: { ...plan, status: "ACTIVE" } });
      }),
    );
    const user = userEvent.setup();
    renderWithQueryClient(<PlansPage />);

    expect(await screen.findByRole("heading", { name: "基础力量", level: 3 })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "查看" }));
    expect(await screen.findByRole("heading", { name: "基础力量", level: 2 })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "关闭详情" }));
    await user.click(screen.getByRole("button", { name: "激活计划" }));

    expect(await screen.findByText("计划已激活")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("button", { name: "激活计划" })).not.toBeInTheDocument());
    expect(screen.getByRole("link", { name: "去训练" })).toHaveAttribute("href", "/workouts");
  });

  it("prefills an editable preset from active catalogue pages and saves only on submit", async () => {
    const exercises: Exercise[] = [
      { id: 109, name: "俯卧撑", englishName: "Push Up" },
      { id: 208, name: "登山跑", englishName: "Mountain Climber" },
      { id: 307, name: "俄罗斯转体", englishName: "Russian Twist" },
      { id: 406, name: "开合跳", englishName: "Jumping Jack" },
    ];
    let saved: PlanCreateInput | undefined;
    const requestedPages: string[] = [];
    server.use(
      http.get("/api/v1/training-plans", () => HttpResponse.json({
        code: 0,
        message: "success",
        data: { items: [], total: 0, page: 1, size: 50, pages: 0 },
      })),
      http.get("/api/v1/exercises", ({ request }) => {
        const page = new URL(request.url).searchParams.get("page");
        if (!page) return exercisePage([]);
        requestedPages.push(page);
        if (page === "1") return exercisePage(exercises.slice(0, 3), 1, 2);
        return exercisePage(exercises.slice(3), 2, 2);
      }),
      http.post("/api/v1/training-plans", async ({ request }) => {
        saved = (await request.json()) as PlanCreateInput;
        return HttpResponse.json({ code: 0, message: "success", data: plan });
      }),
    );
    const user = userEvent.setup();
    renderWithQueryClient(<PlansPage />);

    const presetButton = await screen.findByRole("button", { name: "套用预设：居家徒手体能" });
    expect(requestedPages).toEqual(["1", "2"]);
    expect(screen.getAllByRole("button", { name: "动作暂不可用" })).toHaveLength(2);
    await user.click(presetButton);
    expect(saved).toBeUndefined();
    const name = screen.getByRole("textbox", { name: "计划名称" });
    expect(name).toHaveValue("居家徒手体能");
    expect(screen.getByRole("textbox", { name: "第 2 个训练日名称" })).toHaveValue("协调与核心");
    const firstExercise = screen.getAllByRole("article").find(
      (article) => within(article).queryByRole("button", { name: "移除俯卧撑" }),
    )!;
    const sets = within(firstExercise).getByRole("spinbutton", { name: "组数" });
    await user.clear(sets);
    await user.type(sets, "3");
    await user.clear(name);
    await user.type(name, "我的徒手训练");
    await user.click(screen.getByRole("button", { name: "保存为草稿" }));

    expect(await screen.findByText("计划已创建并保存为草稿")).toBeInTheDocument();
    expect(saved).toMatchObject({
      name: "我的徒手训练",
      goal: "GENERAL_FITNESS",
      days: [
        {
          dayNumber: 1,
          exercises: [
            { exerciseId: 109, sequence: 1, targetSets: 3 },
            { exerciseId: 208, sequence: 2 },
            { exerciseId: 307, sequence: 3 },
          ],
        },
        {
          dayNumber: 2,
          exercises: [
            { exerciseId: 406, sequence: 1 },
            { exerciseId: 109, sequence: 2 },
            { exerciseId: 208, sequence: 3 },
          ],
        },
      ],
    });
  });
});

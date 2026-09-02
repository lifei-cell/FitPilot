import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import type { Plan } from "../../api/types";
import { server } from "../../test/server";
import { PlanDetailPanel } from "./PlanDetailPanel";

const draftPlan: Plan = {
  id: 42,
  name: "基础力量",
  description: "每周稳定推进",
  goal: "STRENGTH",
  durationWeeks: 8,
  daysPerWeek: 1,
  status: "DRAFT",
  version: 1,
  days: [
    {
      id: 11,
      dayNumber: 1,
      name: "上肢力量",
      exercises: [
        {
          id: 21,
          exerciseId: 1,
          exerciseName: "杠铃卧推",
          sequence: 1,
          targetSets: 4,
          targetRepsMin: 5,
          targetRepsMax: 8,
          targetRpe: 8,
          restSeconds: 120,
        },
      ],
    },
  ],
};

function renderPanel(onChanged = vi.fn()) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <PlanDetailPanel planId={42} onClose={vi.fn()} onChanged={onChanged} />
    </QueryClientProvider>,
  );
}

describe("PlanDetailPanel", () => {
  it("loads a draft with MSW, edits it, and submits the current version", async () => {
    let submittedVersion = 0;
    server.use(
      http.get("/api/v1/training-plans/42", () =>
        HttpResponse.json({ code: 0, message: "success", data: draftPlan }),
      ),
      http.get("/api/v1/exercises", () =>
        HttpResponse.json({
          code: 0,
          message: "success",
          data: { items: [], total: 0, page: 1, size: 20, pages: 0 },
        }),
      ),
      http.put("/api/v1/training-plans/42", async ({ request }) => {
        const body = (await request.json()) as { version: number; name: string };
        submittedVersion = body.version;
        return HttpResponse.json({
          code: 0,
          message: "success",
          data: { ...draftPlan, name: body.name, version: 2 },
        });
      }),
    );
    const onChanged = vi.fn();
    const user = userEvent.setup();

    renderPanel(onChanged);
    expect(await screen.findByText("杠铃卧推")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "修改计划" }));
    const name = screen.getByRole("textbox", { name: "计划名称" });
    await user.clear(name);
    await user.type(name, "基础力量 · 调整版");
    await user.click(screen.getByRole("button", { name: "保存修改" }));

    expect(await screen.findByRole("heading", { name: "基础力量 · 调整版" })).toBeInTheDocument();
    expect(submittedVersion).toBe(1);
    expect(onChanged).toHaveBeenCalledWith("计划修改已保存");
  });

  it("keeps the editor open and reports an optimistic-lock conflict", async () => {
    let submittedVersion = 0;
    server.use(
      http.get("/api/v1/training-plans/42", () =>
        HttpResponse.json({ code: 0, message: "success", data: draftPlan }),
      ),
      http.get("/api/v1/exercises", () =>
        HttpResponse.json({
          code: 0,
          message: "success",
          data: { items: [], total: 0, page: 1, size: 20, pages: 0 },
        }),
      ),
      http.put("/api/v1/training-plans/42", async ({ request }) => {
        submittedVersion = (await request.json() as { version: number }).version;
        return HttpResponse.json(
          { code: 30006, message: "计划已被其他设备修改，请刷新后重试", data: null },
          { status: 409 },
        );
      }),
    );
    const onChanged = vi.fn();
    const user = userEvent.setup();
    renderPanel(onChanged);

    await user.click(await screen.findByRole("button", { name: "修改计划" }));
    await user.click(screen.getByRole("button", { name: "保存修改" }));

    expect(await screen.findByText("计划已被其他设备修改，请刷新后重试")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存修改" })).toBeInTheDocument();
    expect(submittedVersion).toBe(1);
    expect(onChanged).not.toHaveBeenCalled();
  });
});

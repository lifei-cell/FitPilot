import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { Plan } from "../api/types";
import { renderWithQueryClient } from "../test/render";
import { server } from "../test/server";
import { PlansPage } from "./PlansPage";

const plan: Plan = {
  id: 42, name: "基础力量", goal: "STRENGTH", durationWeeks: 8,
  daysPerWeek: 1, status: "DRAFT", version: 1,
  days: [{ id: 11, dayNumber: 1, name: "上肢", exercises: [] }],
};

describe("PlansPage", () => {
  it("lists, opens, and activates a draft plan", async () => {
    let active = false;
    server.use(
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
  });
});

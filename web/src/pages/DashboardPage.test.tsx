import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { renderWithQueryClient } from "../test/render";
import { server } from "../test/server";
import { DashboardPage } from "./DashboardPage";

describe("DashboardPage", () => {
  it("renders metrics, the active plan, and an in-progress workout", async () => {
    server.use(
      http.get("/api/v1/analytics/overview", () => HttpResponse.json({ code: 0, message: "success", data: {
        workoutsThisWeek: 3, trainingDurationMinutes: 180, trainingVolume: 12500, personalRecords: 2,
      } })),
      http.get("/api/v1/training-plans/active/current", () => HttpResponse.json({ code: 0, message: "success", data: {
        id: 1, name: "力量进阶", goal: "STRENGTH", daysPerWeek: 3,
        days: [{ id: 11, dayNumber: 1, name: "深蹲日", exercises: [] }],
      } })),
      http.get("/api/v1/workouts/active/current", () => HttpResponse.json({ code: 0, message: "success", data: {
        id: 7, name: "深蹲日", startedAt: "2026-09-02T08:00:00Z", exercises: [{ id: 1 }],
      } })),
    );
    renderWithQueryClient(<DashboardPage />);

    expect(await screen.findByText("训练正在进行")).toBeInTheDocument();
    expect(screen.getByText("12,500")).toBeInTheDocument();
    expect(screen.getByText("深蹲日 · 已加载 1 个动作")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /继续训练/ })).toHaveAttribute("href", "/workouts");
  });
});

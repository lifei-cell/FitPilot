import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthContext";
import { server } from "./test/server";
import { createTestQueryClient } from "./test/render";

beforeEach(() => history.replaceState({}, "", "/"));

function renderApp() {
  const client = createTestQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <AuthProvider><App /></AuthProvider>
    </QueryClientProvider>,
  );
}

describe("App routing", () => {
  it("shows authentication when no access token exists", () => {
    renderApp();
    expect(screen.getByRole("heading", { name: "继续今天的训练" })).toBeInTheDocument();
  });

  it("renders authenticated routes and reacts to shell navigation", async () => {
    sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature");
    server.use(
      http.get("/api/v1/analytics/overview", () => HttpResponse.json({ code: 0, message: "success", data: {
        workoutsThisWeek: 0, trainingDurationMinutes: 0, trainingVolume: 0, personalRecords: 0,
      } })),
      http.get("/api/v1/training-plans/active/current", () =>
        HttpResponse.json({ code: 30001, message: "not found", data: null }, { status: 404 })),
      http.get("/api/v1/workouts/active/current", () =>
        HttpResponse.json({ code: 30002, message: "not found", data: null }, { status: 404 })),
      http.get("/api/v1/exercises", () => HttpResponse.json({ code: 0, message: "success", data: {
        items: [], total: 0, page: 1, size: 60, pages: 0,
      } })),
      http.get("/api/v1/notifications", () =>
        HttpResponse.json({ code: 0, message: "success", data: [] })),
    );
    const user = userEvent.setup();
    renderApp();

    expect(await screen.findByRole("heading", { name: "今天，继续向上。" })).toBeInTheDocument();
    await user.click(screen.getAllByRole("link", { name: "动作库" })[0]);
    expect(await screen.findByRole("heading", { name: "动作库" })).toBeInTheDocument();
    expect(screen.getByText("没有找到动作")).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "通知" }));
    expect(await screen.findByRole("heading", { name: "通知" })).toBeInTheDocument();
  });
});

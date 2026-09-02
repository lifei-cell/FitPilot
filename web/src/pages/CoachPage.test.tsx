import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { AgentPendingAction } from "../api/types";
import { renderWithQueryClient } from "../test/render";
import { server } from "../test/server";
import { CoachPage } from "./CoachPage";

const pendingAction: AgentPendingAction = {
  id: "pending-1",
  toolName: "adjust_training_plan",
  confirmationToken: "confirmation-secret",
  expiresAt: "2099-01-01T00:00:00Z",
  preview: {
    name: "AI 调整计划",
    description: "根据最近训练反馈降低强度",
    goal: "STRENGTH",
    durationWeeks: 6,
    days: [{
      dayNumber: 1,
      name: "恢复训练",
      exercises: [{ exerciseId: 101, sequence: 1, targetSets: 3,
        targetRepsMin: 5, targetRepsMax: 6, targetRpe: 7 }],
    }],
  },
  guardrailWarnings: ["保留疼痛保护规则"],
};

function useCoachHandlers(confirmCode: number, confirmMessage: string) {
  server.use(
    http.get("/api/v1/agent/sessions", () =>
      HttpResponse.json({ code: 0, message: "success", data: { items: [], total: 0, page: 1, size: 100, pages: 0 } })),
    http.get("/api/v1/agent/plan-adjustments", () =>
      HttpResponse.json({ code: 0, message: "success", data: { items: [], total: 0, page: 1, size: 5, pages: 0 } })),
    http.get("/api/v1/exercises", () =>
      HttpResponse.json({ code: 0, message: "success", data: { items: [{ id: 101, name: "杠铃深蹲" }], total: 1, page: 1, size: 100, pages: 1 } })),
    http.post("/api/v1/agent/sessions", () =>
      HttpResponse.json({ code: 0, message: "success", data: { id: "session-1" } })),
    http.post("/api/v1/agent/sessions/session-1/messages", () =>
      HttpResponse.json({ code: 0, message: "success", data: {
        answer: "已生成安全的调整草案。",
        selectedTools: ["get_training_adjustment_context", "adjust_training_plan"],
        degraded: false,
        confirmationRequired: true,
        pendingAction,
      } })),
    http.post("/api/v1/agent/pending-actions/pending-1/confirm", async ({ request }) => {
      expect(await request.json()).toEqual({ confirmationToken: "confirmation-secret" });
      return HttpResponse.json(
        { code: confirmCode, message: confirmMessage, data: null },
        { status: 403 },
      );
    }),
  );
}

async function proposeAndConfirm() {
  sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature");
  const user = userEvent.setup();
  renderWithQueryClient(<CoachPage />);
  const input = screen.getByPlaceholderText(/根据最近训练量/);
  await user.type(input, "根据最近状态调整计划");
  fireEvent.submit(input.closest("form")!);
  expect(await screen.findByText("已生成安全的调整草案。")).toBeInTheDocument();
  expect(await screen.findByText("杠铃深蹲")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "确认并保存草稿" }));
}

describe("CoachPage confirmation safety", () => {
  it("marks an expired confirmation token and prevents another submission", async () => {
    useCoachHandlers(80003, "confirmation expired");
    await proposeAndConfirm();

    expect(await screen.findByText("确认令牌已过期，请重新签发或生成计划。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "确认并保存草稿" })).toBeDisabled();
  });

  it("removes an already processed action instead of replaying it", async () => {
    useCoachHandlers(80005, "already processed");
    await proposeAndConfirm();

    expect(await screen.findByText("该计划已经处理，不能重复确认。")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "确认并保存草稿" })).not.toBeInTheDocument();
  });
});

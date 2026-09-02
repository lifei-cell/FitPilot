import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { clearPendingAction, storePendingAction } from "../agent/pendingActionStorage";
import { storeAgentSession } from "../agent/sessionStorage";
import { AUTH_EXPIRED_EVENT } from "../api/client";
import { server } from "../test/server";
import type { AgentPendingAction } from "../api/types";
import { AuthProvider, useAuth } from "./AuthContext";

function Harness() {
  const auth = useAuth();
  return (
    <div>
      <span>{auth.authenticated ? "authenticated" : "anonymous"}</span>
      <button onClick={() => void auth.login("athlete", "password123")}>login</button>
      <button onClick={() => void auth.register("newbie", "new@example.com", "password123")}>register</button>
      <button onClick={() => void auth.logout()}>logout</button>
    </div>
  );
}

const pending: AgentPendingAction = {
  id: "pending-1",
  toolName: "create_training_plan",
  confirmationToken: "secret",
  expiresAt: "2099-01-01T00:00:00Z",
  preview: { name: "草稿", goal: "STRENGTH", durationWeeks: 8, days: [] },
  guardrailWarnings: [],
};

describe("AuthProvider", () => {
  it("logs in and reacts to a rejected refresh session", async () => {
    server.use(
      http.post("/api/v1/auth/login", () =>
        HttpResponse.json({
          code: 0,
          message: "success",
          data: { accessToken: "header.eyJzdWIiOiI5OSJ9.signature" },
        }),
      ),
    );
    const user = userEvent.setup();
    render(<AuthProvider><Harness /></AuthProvider>);

    await user.click(screen.getByRole("button", { name: "login" }));
    expect(await screen.findByText("authenticated")).toBeInTheDocument();
    storeAgentSession("99", "session-1");
    storePendingAction("99", pending);
    window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT, { detail: "99" }));
    expect(await screen.findByText("anonymous")).toBeInTheDocument();
    expect(sessionStorage.getItem("fitpilot_agent_session:99")).toBeNull();
    expect(sessionStorage.getItem("fitpilot_agent_pending_99")).toBeNull();
  });

  it("registers, logs in, and clears all user state even when logout fails", async () => {
    let registered = false;
    server.use(
      http.post("/api/v1/auth/register", async ({ request }) => {
        registered = (await request.json() as { email: string }).email === "new@example.com";
        return HttpResponse.json({ code: 0, message: "success", data: null });
      }),
      http.post("/api/v1/auth/login", () =>
        HttpResponse.json({
          code: 0,
          message: "success",
          data: { accessToken: "header.eyJzdWIiOiI5OSJ9.signature" },
        }),
      ),
      http.post("/api/v1/auth/logout", () =>
        HttpResponse.json(
          { code: 50000, message: "logout unavailable", data: null },
          { status: 503 },
        ),
      ),
    );
    const user = userEvent.setup();
    render(<AuthProvider><Harness /></AuthProvider>);

    await user.click(screen.getByRole("button", { name: "register" }));
    expect(await screen.findByText("authenticated")).toBeInTheDocument();
    expect(registered).toBe(true);
    storeAgentSession("99", "session-1");
    storePendingAction("99", pending);
    await user.click(screen.getByRole("button", { name: "logout" }));

    await waitFor(() => expect(screen.getByText("anonymous")).toBeInTheDocument());
    expect(sessionStorage.getItem("fitpilot_access")).toBeNull();
    expect(sessionStorage.getItem("fitpilot_agent_session:99")).toBeNull();
    expect(sessionStorage.getItem("fitpilot_agent_pending_99")).toBeNull();
    clearPendingAction("99");
  });

  it("rejects useAuth outside its provider", () => {
    expect(() => render(<Harness />)).toThrow("AuthProvider is missing");
  });
});

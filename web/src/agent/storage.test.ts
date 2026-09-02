import { describe, expect, it } from "vitest";
import type { AgentPendingAction } from "../api/types";
import {
  clearPendingAction,
  loadPendingAction,
  storePendingAction,
} from "./pendingActionStorage";
import {
  clearAgentSession,
  loadAgentSession,
  storeAgentSession,
} from "./sessionStorage";

const action: AgentPendingAction = {
  id: "pending-1",
  toolName: "create_training_plan",
  confirmationToken: "token",
  expiresAt: "2099-01-01T00:00:00Z",
  preview: { name: "计划", goal: "STRENGTH", durationWeeks: 8, days: [] },
  guardrailWarnings: [],
};

describe("agent session storage", () => {
  it("namespaces conversation and pending action state by token subject", () => {
    storeAgentSession("99", "session-1");
    storePendingAction("99", action);
    expect(loadAgentSession("99")).toBe("session-1");
    expect(loadPendingAction("99")).toEqual(action);

    clearAgentSession("99");
    clearPendingAction("99");
    expect(loadAgentSession("99")).toBeNull();
    expect(loadPendingAction("99")).toBeNull();
  });

  it("ignores anonymous state and removes malformed pending JSON", () => {
    storeAgentSession(null, "ignored");
    storePendingAction(null, action);
    expect(loadAgentSession(null)).toBeNull();
    expect(loadPendingAction(null)).toBeNull();

    sessionStorage.setItem("fitpilot_agent_pending_99", "not-json");
    expect(loadPendingAction("99")).toBeNull();
    expect(sessionStorage.getItem("fitpilot_agent_pending_99")).toBeNull();
  });
});

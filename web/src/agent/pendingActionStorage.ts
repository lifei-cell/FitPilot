import type { AgentPendingAction } from "../api/types";

const prefix = "fitpilot_agent_pending_";

function key(userId: string | null) {
  return userId ? `${prefix}${userId}` : "";
}

export function loadPendingAction(
  userId: string | null,
): AgentPendingAction | null {
  const storageKey = key(userId);
  if (!storageKey) return null;
  const value = sessionStorage.getItem(storageKey);
  if (!value) return null;
  try {
    return JSON.parse(value) as AgentPendingAction;
  } catch {
    sessionStorage.removeItem(storageKey);
    return null;
  }
}

export function storePendingAction(
  userId: string | null,
  action: AgentPendingAction,
) {
  const storageKey = key(userId);
  if (storageKey) sessionStorage.setItem(storageKey, JSON.stringify(action));
}

export function clearPendingAction(userId: string | null) {
  const storageKey = key(userId);
  if (storageKey) sessionStorage.removeItem(storageKey);
}

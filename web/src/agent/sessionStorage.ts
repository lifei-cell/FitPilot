const PREFIX = "fitpilot_agent_session:";

function key(userId: string) {
  return `${PREFIX}${userId}`;
}

export function loadAgentSession(userId: string | null) {
  return userId ? sessionStorage.getItem(key(userId)) : null;
}

export function storeAgentSession(userId: string | null, sessionId: string) {
  if (userId) sessionStorage.setItem(key(userId), sessionId);
}

export function clearAgentSession(userId: string | null) {
  if (userId) sessionStorage.removeItem(key(userId));
}

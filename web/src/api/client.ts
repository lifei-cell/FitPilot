export type ApiEnvelope<T> = { code: number; message: string; data: T };

const API = "/api/v1";
export const AUTH_EXPIRED_EVENT = "fitpilot:auth-expired";
let refreshPromise: Promise<boolean> | null = null;

export function accessToken() {
  return sessionStorage.getItem("fitpilot_access");
}
export function storeToken(token: string) {
  sessionStorage.setItem("fitpilot_access", token);
}
export function clearToken() {
  sessionStorage.removeItem("fitpilot_access");
}

/** The subject is used only to namespace browser state; the server still enforces ownership. */
export function accessTokenSubject(): string | null {
  const token = accessToken();
  if (!token) return null;
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const json = atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "="));
    const subject = (JSON.parse(json) as { sub?: unknown }).sub;
    return typeof subject === "string" && subject.length > 0 ? subject : null;
  } catch {
    return null;
  }
}

async function refresh(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = fetch(`${API}/auth/refresh`, {
      method: "POST",
      credentials: "include",
    })
      .then(async (response) => {
        if (!response.ok) {
          const subject = accessTokenSubject();
          clearToken();
          window.dispatchEvent(
            new CustomEvent(AUTH_EXPIRED_EVENT, { detail: subject }),
          );
          return false;
        }
        const body = (await response.json()) as ApiEnvelope<{
          accessToken: string;
        }>;
        storeToken(body.data.accessToken);
        return true;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

export async function api<T>(
  path: string,
  init: RequestInit = {},
  retry = true,
): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type"))
    headers.set("Content-Type", "application/json");
  const token = accessToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(`${API}${path}`, {
    ...init,
    headers,
    credentials: "include",
  });
  if (response.status === 401 && retry && (await refresh()))
    return api<T>(path, init, false);
  const body = (await response
    .json()
    .catch(() => null)) as ApiEnvelope<T> | null;
  if (!response.ok || !body)
    throw new ApiError(
      body?.message ?? "请求失败",
      response.status,
      body?.code,
    );
  return body.data;
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public code?: number,
  ) {
    super(message);
  }
}

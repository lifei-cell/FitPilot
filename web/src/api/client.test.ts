import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "../test/server";
import {
  accessToken,
  accessTokenSubject,
  api,
  ApiError,
  AUTH_EXPIRED_EVENT,
  clearToken,
  storeToken,
} from "./client";

describe("API client", () => {
  it("unwraps successful responses and sends the access token", async () => {
    storeToken("test-token");
    server.use(
      http.get("/api/v1/exercises", ({ request }) => {
        expect(request.headers.get("Authorization")).toBe("Bearer test-token");
        return HttpResponse.json({
          code: 0,
          message: "success",
          data: { items: [{ id: 1, name: "杠铃卧推" }] },
        });
      }),
    );

    await expect(api<{ items: { id: number }[] }>("/exercises")).resolves.toEqual({
      items: [{ id: 1, name: "杠铃卧推" }],
    });
  });

  it("preserves backend error codes", async () => {
    server.use(
      http.get("/api/v1/training-plans/404", () =>
        HttpResponse.json(
          { code: 30001, message: "training plan not found", data: null },
          { status: 404 },
        ),
      ),
    );

    const error = await api("/training-plans/404").catch((reason) => reason);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 404, code: 30001 });
  });

  it("refreshes an expired token once and retries concurrent requests", async () => {
    storeToken("expired-token");
    let refreshes = 0;
    server.use(
      http.post("/api/v1/auth/refresh", () => {
        refreshes += 1;
        return HttpResponse.json({
          code: 0,
          message: "success",
          data: { accessToken: "fresh-token" },
        });
      }),
      http.get("/api/v1/users/me", ({ request }) => {
        if (request.headers.get("Authorization") !== "Bearer fresh-token") {
          return HttpResponse.json(
            { code: 10001, message: "expired", data: null },
            { status: 401 },
          );
        }
        return HttpResponse.json({
          code: 0,
          message: "success",
          data: { username: "athlete" },
        });
      }),
    );

    const [first, second] = await Promise.all([
      api<{ username: string }>("/users/me"),
      api<{ username: string }>("/users/me"),
    ]);

    expect(first.username).toBe("athlete");
    expect(second.username).toBe("athlete");
    expect(refreshes).toBe(1);
    expect(accessToken()).toBe("fresh-token");
  });

  it("clears browser authentication when refresh is rejected", async () => {
    storeToken("expired-token");
    const expired = vi.fn();
    window.addEventListener(AUTH_EXPIRED_EVENT, expired, { once: true });
    server.use(
      http.get("/api/v1/users/me", () =>
        HttpResponse.json(
          { code: 10001, message: "expired", data: null },
          { status: 401 },
        ),
      ),
      http.post("/api/v1/auth/refresh", () =>
        HttpResponse.json(
          { code: 10002, message: "refresh expired", data: null },
          { status: 401 },
        ),
      ),
    );

    await expect(api("/users/me")).rejects.toMatchObject({ status: 401 });
    expect(accessToken()).toBeNull();
    expect(expired).toHaveBeenCalledOnce();
  });

  it("decodes the token subject and rejects malformed tokens", () => {
    storeToken("header.eyJzdWIiOiI5OSJ9.signature");
    expect(accessTokenSubject()).toBe("99");
    storeToken("not-a-jwt");
    expect(accessTokenSubject()).toBeNull();
    clearToken();
    expect(accessTokenSubject()).toBeNull();
  });
});

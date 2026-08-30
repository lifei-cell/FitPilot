import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "../test/server";
import { api, ApiError, storeToken } from "./client";

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
});

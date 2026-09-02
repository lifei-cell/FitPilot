import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { AuthProvider } from "../auth/AuthContext";
import { server } from "../test/server";
import { AuthPage } from "./AuthPage";

describe("AuthPage", () => {
  it("shows login errors, then switches to registration and creates an account", async () => {
    let registered: unknown;
    server.use(
      http.post("/api/v1/auth/login", async ({ request }) => {
        const body = await request.json() as { username: string };
        if (body.username === "blocked") {
          return HttpResponse.json(
            { code: 10003, message: "用户名或密码错误", data: null },
            { status: 401 },
          );
        }
        return HttpResponse.json({ code: 0, message: "success", data: {
          accessToken: "header.eyJzdWIiOiI5OSJ9.signature",
        } });
      }),
      http.post("/api/v1/auth/register", async ({ request }) => {
        registered = await request.json();
        return HttpResponse.json({ code: 0, message: "success", data: null });
      }),
    );
    const user = userEvent.setup();
    render(<AuthProvider><AuthPage /></AuthProvider>);

    await user.type(screen.getByPlaceholderText("fitpilot_user"), "blocked");
    await user.type(screen.getByPlaceholderText("至少 8 位"), "password123");
    await user.click(screen.getByRole("button", { name: /进入训练台/ }));
    expect(await screen.findByText("用户名或密码错误")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "还没有账户？立即注册" }));
    await user.clear(screen.getByPlaceholderText("fitpilot_user"));
    await user.type(screen.getByPlaceholderText("fitpilot_user"), "newbie");
    await user.type(screen.getByPlaceholderText("you@example.com"), "new@example.com");
    await user.clear(screen.getByPlaceholderText("至少 8 位"));
    await user.type(screen.getByPlaceholderText("至少 8 位"), "password123");
    await user.click(screen.getByRole("button", { name: /创建账户/ }));

    expect(registered).toEqual({ username: "newbie", email: "new@example.com", password: "password123" });
    expect(sessionStorage.getItem("fitpilot_access")).toContain("eyJzdWIiOiI5OSJ9");
    expect(location.pathname).toBe("/");
  });
});

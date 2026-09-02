import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../auth/AuthContext";
import { server } from "../test/server";
import { AppShell, navigate } from "./AppShell";

describe("AppShell", () => {
  it("navigates without a reload and performs local logout", async () => {
    sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature");
    server.use(
      http.post("/api/v1/auth/logout", () =>
        HttpResponse.json({ code: 0, message: "success", data: null })),
    );
    const popstate = vi.fn();
    window.addEventListener("popstate", popstate);
    const user = userEvent.setup();
    render(<AuthProvider><AppShell><p>content</p></AppShell></AuthProvider>);

    await user.click(screen.getAllByRole("link", { name: "训练计划" })[0]);
    expect(location.pathname).toBe("/plans");
    expect(popstate).toHaveBeenCalled();
    navigate("/coach");
    expect(location.pathname).toBe("/coach");

    await user.click(screen.getByRole("button", { name: "退出登录" }));
    expect(sessionStorage.getItem("fitpilot_access")).toBeNull();
    window.removeEventListener("popstate", popstate);
  });
});

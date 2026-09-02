import { expect, test, type Page } from "@playwright/test";

const token = "header.eyJzdWIiOiI5OSJ9.signature";

async function authenticated(page: Page) {
  await page.addInitScript((accessToken) => {
    sessionStorage.setItem("fitpilot_access", accessToken);
  }, token);
}

test("过期 Access Token 自动刷新后重放原请求", async ({ page }) => {
  let refreshes = 0;
  let freshRequests = 0;
  await page.addInitScript(() => sessionStorage.setItem("fitpilot_access", "expired-token"));
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/v1/auth/refresh") {
      refreshes += 1;
      return route.fulfill({ json: { code: 0, message: "success", data: { accessToken: "fresh-token" } } });
    }
    if (path === "/api/v1/exercises") {
      if (request.headers().authorization !== "Bearer fresh-token") {
        return route.fulfill({ status: 401, json: { code: 10001, message: "expired", data: null } });
      }
      freshRequests += 1;
      return route.fulfill({ json: { code: 0, message: "success", data: {
        items: [{ id: 1, name: "杠铃卧推", primaryMuscle: "胸部", equipment: "杠铃" }],
        total: 1, page: 1, size: 60, pages: 1,
      } } });
    }
    return route.fulfill({ status: 404, json: { code: 404, message: "unmocked", data: null } });
  });

  await page.goto("/exercises");
  await expect(page.getByRole("button", { name: /杠铃卧推/ })).toBeVisible();
  expect(refreshes).toBe(1);
  expect(freshRequests).toBe(1);
});

test("从计划开始 Workout、记录一组并完成训练", async ({ page }) => {
  let active = false;
  let setBody: Record<string, unknown> | undefined;
  let completionBody: Record<string, unknown> | undefined;
  await authenticated(page);
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const ok = (data: unknown) => route.fulfill({ json: { code: 0, message: "success", data } });
    const workout = () => ({ id: 7, name: "上肢力量", status: "IN_PROGRESS", startedAt: "2026-09-02T08:00:00Z",
      exercises: [{ id: 71, exerciseId: 101, exerciseName: "杠铃卧推", sequence: 1,
        targetSets: 4, targetRepsMin: 5, targetRepsMax: 8, targetRpe: 8,
        sets: setBody ? [{ id: 701, setNumber: 1, ...setBody }] : [] }] });
    if (path === "/api/v1/workouts/active/current") {
      return active ? ok(workout()) : route.fulfill({ status: 404, json: { code: 30002, message: "no active workout", data: null } });
    }
    if (path === "/api/v1/training-plans/active/current") return ok({ id: 1, name: "力量计划", days: [{
      id: 11, dayNumber: 1, name: "上肢力量", exercises: [{ id: 21 }],
    }] });
    if (path === "/api/v1/workouts" && request.method() === "GET") return ok({ items: [], total: 0, page: 1, size: 10, pages: 0 });
    if (path === "/api/v1/workouts" && request.method() === "POST") { active = true; return ok(workout()); }
    if (path === "/api/v1/workouts/7/exercises/71/sets") {
      setBody = request.postDataJSON() as Record<string, unknown>;
      return ok({ id: 701, setNumber: 1, ...setBody });
    }
    if (path === "/api/v1/workouts/7/complete") {
      completionBody = request.postDataJSON() as Record<string, unknown>;
      active = false;
      return ok({ workoutId: 7 });
    }
    return route.fulfill({ status: 404, json: { code: 404, message: "unmocked", data: null } });
  });

  await page.goto("/workouts");
  await page.getByRole("button", { name: /上肢力量/ }).click();
  await page.getByPlaceholder("重量 kg").fill("80");
  await page.getByPlaceholder("次数").fill("5");
  await page.getByPlaceholder("RPE").fill("8");
  await page.getByRole("button", { name: "记录一组" }).click();
  await expect(page.getByText("80 kg")).toBeVisible();
  await page.getByRole("button", { name: "完成训练" }).click();
  await page.getByRole("button", { name: "提交并完成" }).click();
  await expect(page.getByText("训练已记录")).toBeVisible();

  expect(setBody).toMatchObject({ weightKg: 80, reps: 5, rpe: 8 });
  expect(completionBody).toEqual({ feedback: { fatigueScore: 5, painScore: 0 } });
});

test("Agent 确认令牌过期后禁止重放", async ({ page }) => {
  await authenticated(page);
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const ok = (data: unknown) => route.fulfill({ json: { code: 0, message: "success", data } });
    if (path === "/api/v1/agent/sessions" && request.method() === "GET") return ok({ items: [], total: 0, page: 1, size: 100, pages: 0 });
    if (path === "/api/v1/agent/plan-adjustments") return ok({ items: [], total: 0, page: 1, size: 5, pages: 0 });
    if (path === "/api/v1/agent/sessions" && request.method() === "POST") return ok({ id: "session-1" });
    if (path === "/api/v1/agent/sessions/session-1/messages") return ok({
      answer: "已生成调整草案。", selectedTools: ["adjust_training_plan"], degraded: false,
      confirmationRequired: true, pendingAction: {
        id: "pending-1", toolName: "adjust_training_plan", confirmationToken: "expired-secret",
        expiresAt: "2099-01-01T00:00:00Z", guardrailWarnings: [], preview: {
          name: "调整计划", goal: "STRENGTH", durationWeeks: 6,
          days: [{ dayNumber: 1, name: "恢复训练", exercises: [] }],
        },
      },
    });
    if (path === "/api/v1/exercises") return ok({ items: [], total: 0, page: 1, size: 100, pages: 0 });
    if (path === "/api/v1/agent/pending-actions/pending-1/confirm") {
      return route.fulfill({ status: 403, json: { code: 80003, message: "confirmation expired", data: null } });
    }
    return route.fulfill({ status: 404, json: { code: 404, message: "unmocked", data: null } });
  });

  await page.goto("/coach");
  await page.getByPlaceholder(/根据最近训练量/).fill("调整训练计划");
  await page.getByPlaceholder(/根据最近训练量/).press("Enter");
  await page.getByRole("button", { name: "确认并保存草稿" }).click();
  await expect(page.getByText("确认令牌已过期，请重新签发或生成计划。")).toBeVisible();
  await expect(page.getByRole("button", { name: "确认并保存草稿" })).toBeDisabled();
});

test("计划并发冲突保留编辑内容并提示刷新", async ({ page }) => {
  await authenticated(page);
  const plan = { id: 42, name: "基础力量", goal: "STRENGTH", durationWeeks: 8, daysPerWeek: 1,
    status: "DRAFT", version: 3, days: [{ id: 11, dayNumber: 1, name: "上肢", exercises: [{
      id: 21, exerciseId: 1, exerciseName: "杠铃卧推", sequence: 1,
      targetSets: 4, targetRepsMin: 5, targetRepsMax: 8, targetRpe: 8, restSeconds: 120,
    }] }] };
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const ok = (data: unknown) => route.fulfill({ json: { code: 0, message: "success", data } });
    if (path === "/api/v1/training-plans" && request.method() === "GET") return ok({ items: [plan], total: 1, page: 1, size: 50, pages: 1 });
    if (path === "/api/v1/training-plans/42" && request.method() === "GET") return ok(plan);
    if (path === "/api/v1/exercises") return ok({ items: [], total: 0, page: 1, size: 20, pages: 0 });
    if (path === "/api/v1/training-plans/42" && request.method() === "PUT") {
      expect((request.postDataJSON() as { version: number }).version).toBe(3);
      return route.fulfill({ status: 409, json: {
        code: 30006, message: "计划已被其他设备修改，请刷新后重试", data: null,
      } });
    }
    return route.fulfill({ status: 404, json: { code: 404, message: "unmocked", data: null } });
  });

  await page.goto("/plans");
  await page.getByRole("button", { name: "查看" }).click();
  await page.getByRole("button", { name: "修改计划" }).click();
  await page.getByRole("textbox", { name: "计划名称" }).fill("未覆盖的并发编辑");
  await page.getByRole("button", { name: "保存修改" }).click();

  await expect(page.getByText("计划已被其他设备修改，请刷新后重试")).toBeVisible();
  await expect(page.getByRole("textbox", { name: "计划名称" })).toHaveValue("未覆盖的并发编辑");
});

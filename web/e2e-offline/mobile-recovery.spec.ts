import { devices, expect, test } from "@playwright/test";

const mobile = { ...devices["Pixel 5"], viewport: { width: 393, height: 851 } };

function workout(state: { closed: boolean; sets: Record<string, unknown>[]; restSeconds?: number }) {
  return {
    id: 7,
    name: "移动端冲突训练",
    status: state.closed ? "COMPLETED" : "IN_PROGRESS",
    startedAt: "2026-09-12T08:00:00Z",
    exercises: [{ id: 71, exerciseId: 1, exerciseName: "卧推", sequence: 1,
      restSeconds: state.restSeconds, sets: state.sets }],
  };
}

function installToken(context: import("@playwright/test").BrowserContext) {
  return context.addInitScript(() => {
    sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature");
  });
}

test.use(mobile);

test.describe("移动端弱网与跨设备恢复", () => {

  test("弱网延迟和响应丢失后保持本机队列并用原操作号重试", async ({ context, page }) => {
    test.setTimeout(60_000);
    await installToken(context);
    const start = new Date("2026-09-12T08:00:00Z");
    await page.clock.setFixedTime(start);
    const state = { closed: false, sets: [] as Record<string, unknown>[], restSeconds: 90 };
    const receipts = new Map<string, Record<string, unknown>>();
    let loseNextAcknowledgement = true;
    const delay = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds));
    const ok = (data: unknown) => ({ status: 200, contentType: "application/json",
      body: JSON.stringify({ code: 0, message: "success", data }) });

    await context.route("**/api/v1/**", async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path === "/api/v1/workouts/active/current" || path === "/api/v1/workouts/7") {
        return route.fulfill(ok(workout(state)));
      }
      if (path.endsWith("/previous-sets")) return route.fulfill(ok([]));
      if (path === "/api/v1/workouts") return route.fulfill(ok({ items: [], total: 0, page: 1, size: 10, pages: 0 }));
      if (path.endsWith("/sets")) {
        const id = request.headers()["client-operation-id"];
        if (!receipts.has(id)) {
          const set = { ...request.postDataJSON(), id: 701 + state.sets.length,
            setNumber: state.sets.length + 1, clientOperationId: id };
          receipts.set(id, set);
          state.sets.push(set);
        }
        await delay(1_200);
        if (loseNextAcknowledgement) {
          loseNextAcknowledgement = false;
          return route.abort("connectionreset");
        }
        return route.fulfill(ok(receipts.get(id)));
      }
      return route.fulfill({ status: 404, contentType: "application/json",
        body: JSON.stringify({ code: 404, message: "not found" }) });
    });

    await page.goto("/workouts");
    await expect(page.getByRole("heading", { name: "移动端冲突训练" })).toBeVisible();
    await page.getByLabel("重量 kg", { exact: true }).fill("62.5");
    await page.getByLabel("次数", { exact: true }).fill("8");
    await page.getByRole("button", { name: "记录一组", exact: true }).click();
    await expect(page.getByRole("status", { name: "训练同步状态" })).toContainText("1 组待同步");
    const timer = page.getByRole("timer", { name: "组间休息剩余时间" });
    await expect(timer).toHaveText("01:30");
    await expect(page.getByRole("button", { name: "完成训练", exact: true })).toBeDisabled();
    await expect(page.getByRole("button", { name: "重试同步" })).toBeEnabled();

    await page.clock.setFixedTime(new Date(start.getTime() + 40_000));
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
    await expect(timer).toHaveText("00:50");
    await page.getByRole("button", { name: "重试同步" }).click();
    await expect.poll(() => state.sets.length, { timeout: 20_000 }).toBe(1);
    await expect(page.getByRole("status", { name: "训练同步状态" })).toContainText("训练记录已同步");
    expect([...receipts.keys()]).toHaveLength(1);
    await expect(timer).toHaveText("00:50");
    await page.close();
  });

  test("同一账户两台设备交错操作时保留冲突记录并显示服务器已结束", async ({ browser }) => {
    test.setTimeout(60_000);
    const contextA = await browser.newContext(mobile);
    const contextB = await browser.newContext(mobile);
    await installToken(contextA);
    await installToken(contextB);
    const state = { closed: false, sets: [{ id: 701, setNumber: 1, weightKg: 60, reps: 10,
      isWarmup: false, isFailure: false }] as Record<string, unknown>[] };
    let deviceAHasObservedClosure = false;
    const createHandler = (isDeviceA: boolean) => async (route: import("@playwright/test").Route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const json = (data: unknown) => route.fulfill({ status: 200, contentType: "application/json",
        body: JSON.stringify({ code: 0, message: "success", data }) });
      if (path === "/api/v1/workouts/active/current") {
        if (state.closed) {
          if (isDeviceA && !deviceAHasObservedClosure) {
            return json(workout({ closed: false, sets: state.sets }));
          }
          return route.fulfill({ status: 404, contentType: "application/json",
            body: JSON.stringify({ code: 40001, message: "workout resource not found" }) });
        }
        return json(workout(state));
      }
      if (path === "/api/v1/workouts/7") return json(workout(state));
      if (path.endsWith("/previous-sets")) return json([]);
      if (path === "/api/v1/workouts") return json({ items: [], total: 0, page: 1, size: 10, pages: 0 });
      if (path === "/api/v1/workouts/7/complete") {
        state.closed = true;
        return json({ workout: workout(state), newPersonalRecords: 0 });
      }
      if (path.endsWith("/sets") && state.closed) {
        return route.fulfill({ status: 409, contentType: "application/json",
          body: JSON.stringify({ code: 40002, message: "训练已结束" }) });
      }
      return route.fulfill({ status: 404, contentType: "application/json",
        body: JSON.stringify({ code: 404, message: "not found" }) });
    };
    await contextA.route("**/api/v1/**", createHandler(true));
    await contextB.route("**/api/v1/**", createHandler(false));
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();
    try {
      await pageA.goto("/workouts");
      await pageB.goto("/workouts");
      const completion = await pageB.evaluate(async () => {
        const response = await fetch("/api/v1/workouts/7/complete", { method: "POST" });
        return response.status;
      });
      expect(completion).toBe(200);

      await pageA.getByLabel("重量 kg", { exact: true }).fill("62.5");
      await pageA.getByLabel("次数", { exact: true }).fill("8");
      await pageA.getByRole("button", { name: /^(记录一组|完成一组)$/ }).click();
      await expect(pageA.getByRole("status", { name: "训练同步状态" })).toContainText("1 组待同步");
      await expect(pageA.getByRole("alert")).toContainText("同步暂停");
      await expect(pageA.getByRole("button", { name: "完成训练", exact: true })).toBeDisabled();

      deviceAHasObservedClosure = true;
      await pageA.reload();
      await expect(pageA.getByText("这次训练已在服务器结束，本机待同步记录仍保留，请核对。"))
        .toBeVisible();
      await expect(pageA.getByRole("status", { name: "训练同步状态" })).toContainText("1 组待同步");
    } finally {
      await contextA.close();
      await contextB.close();
    }
  });
});

import { expect, test } from "@playwright/test";

test("生产页面离线重载、草稿恢复、关页后继续及重连去重", async ({ context, page }) => {
  test.setTimeout(70_000);
  await context.addInitScript(() => sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature"));
  let offline = false;
  let loseAcknowledgement = true;
  const sets: Record<string, unknown>[] = [];
  const receipts = new Map<string, Record<string, unknown>>();
  await context.route("**/api/v1/**", async (route) => {
    if (offline) return route.abort("internetdisconnected");
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const ok = (data: unknown) => route.fulfill({ json: { code: 0, data } });
    if (path === "/api/v1/workouts/active/current" || path === "/api/v1/workouts/7") return ok({
      id: 7, name: "离线卧推训练", status: "IN_PROGRESS", startedAt: "2026-09-12T08:00:00Z",
      exercises: [{ id: 71, exerciseId: 1, exerciseName: "卧推", sequence: 1, sets }],
    });
    if (path.endsWith("/previous-sets")) return ok([]);
    if (path === "/api/v1/workouts") return ok({ items: [], total: 0 });
    if (path.endsWith("/71/sets")) {
      const id = request.headers()["client-operation-id"];
      if (!receipts.has(id)) {
        const set = { ...request.postDataJSON(), id: 701 + sets.length, setNumber: sets.length + 1, clientOperationId: id };
        receipts.set(id, set);
        sets.push(set);
      }
      if (loseAcknowledgement) { loseAcknowledgement = false; return route.abort("connectionreset"); }
      return ok(receipts.get(id));
    }
    return route.fulfill({ status: 404, json: { code: 404, message: "not found" } });
  });
  await page.goto("/workouts");
  await expect(page.getByRole("heading", { name: "离线卧推训练" })).toBeVisible();
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  await page.getByLabel("重量 kg", { exact: true }).fill("62.5");
  await page.getByLabel("次数", { exact: true }).fill("8");
  offline = true;
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByLabel("重量 kg", { exact: true })).toHaveValue("62.5");
  await expect(page.getByLabel("次数", { exact: true })).toHaveValue("8");
  expect(sets).toHaveLength(0);
  await page.getByRole("button", { name: "记录一组", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("1 组待同步");
  await expect(page.getByRole("button", { name: "完成训练", exact: true })).toBeDisabled();
  await page.close();
  const restored = await context.newPage();
  await restored.goto("http://127.0.0.1:4175/workouts");
  await expect(restored.getByRole("status")).toContainText("1 组待同步");
  await restored.getByRole("button", { name: "完成一组", exact: true }).click();
  await expect(restored.getByRole("status")).toContainText("2 组待同步");
  offline = false;
  await context.setOffline(false);
  await expect.poll(() => sets.length, { timeout: 35_000 }).toBeGreaterThan(0);
  await expect(restored.getByRole("button", { name: "重试同步" })).toBeEnabled();
  await restored.getByRole("button", { name: "重试同步" }).click();
  await expect(restored.getByRole("status")).toContainText("训练记录已同步");
  expect(sets).toHaveLength(2);
  expect(receipts.size).toBe(2);
  await restored.reload();
  await expect(restored.getByRole("status")).toContainText("训练记录已同步");
  expect(sets).toHaveLength(2);
});

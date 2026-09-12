import { expect, test } from "@playwright/test";

test("手机宽度下沿用历史并单击完成，下一组预填且不自动提交", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript(() => sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature"));
  const saved: Record<string, unknown>[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const ok = (data: unknown) => route.fulfill({ json: { code: 0, data } });
    if (path.endsWith("/active/current") && path.includes("workouts")) return ok({
      id: 7, name: "卧推训练", status: "IN_PROGRESS", startedAt: "2026-09-12T08:00:00Z",
      exercises: [{ id: 71, exerciseId: 1, exerciseName: "卧推", sequence: 1, sets: saved }],
    });
    if (path.endsWith("/previous-sets")) return ok([
      { id: 1, setNumber: 1, weightKg: 60, reps: 10, isWarmup: false, isFailure: false },
      { id: 2, setNumber: 2, weightKg: 60, reps: 8, isWarmup: false, isFailure: false },
    ]);
    if (path.endsWith("/71/sets")) {
      expect(route.request().headers()["idempotency-key"]).toBeTruthy();
      const set = { ...route.request().postDataJSON(), id: 700 + saved.length, setNumber: saved.length + 1 };
      saved.push(set);
      return ok(set);
    }
    if (path === "/api/v1/workouts") return ok({ items: [], total: 0 });
    return route.fulfill({ status: 404, json: { code: 404, message: "not found" } });
  });
  await page.goto("/workouts");
  await expect(page.getByLabel("重量 kg", { exact: true })).toHaveValue("60");
  await expect(page.getByLabel("次数", { exact: true })).toHaveValue("10");
  expect(saved).toHaveLength(0);
  await page.getByRole("button", { name: "完成一组", exact: true }).click();
  await expect(page.getByLabel("次数", { exact: true })).toHaveValue("8");
  expect(saved).toHaveLength(1);
  expect(saved[0]).toMatchObject({ weightKg: 60, reps: 10, isWarmup: false, isFailure: false });
  expect(saved[0]).not.toHaveProperty("rpe");
  await page.getByLabel("重量 kg", { exact: true }).fill("62.5");
  await page.getByRole("button", { name: "完成一组", exact: true }).click();
  await expect.poll(() => saved.length).toBe(2);
  expect(saved[1]).toMatchObject({ weightKg: 62.5, reps: 8 });
});

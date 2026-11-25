import { expect, test } from "@playwright/test";

test("手机宽度下沿用历史并单击完成，下一组预填且不自动提交", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const start = new Date("2026-09-12T08:00:00Z");
  await page.clock.setFixedTime(start);
  await page.addInitScript(() => sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature"));
  const saved: Record<string, unknown>[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const ok = (data: unknown) => route.fulfill({ json: { code: 0, data } });
    if ((path.endsWith("/active/current") && path.includes("workouts")) || path === "/api/v1/workouts/7") return ok({
      id: 7, name: "卧推训练", status: "IN_PROGRESS", startedAt: "2026-09-12T08:00:00Z",
      exercises: [{ id: 71, exerciseId: 1, exerciseName: "卧推", sequence: 1, restSeconds: 90, sets: saved }],
    });
    if (path.endsWith("/previous-sets")) return ok([
      { id: 1, setNumber: 1, weightKg: 60, reps: 10, isWarmup: false, isFailure: false },
      { id: 2, setNumber: 2, weightKg: 60, reps: 8, isWarmup: false, isFailure: false },
    ]);
    if (path.endsWith("/71/sets")) {
      expect(route.request().headers()["client-operation-id"]).toBeTruthy();
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
  const timer = page.getByRole("timer", { name: "组间休息剩余时间" });
  await expect(timer).toHaveCount(0);
  await page.getByRole("button", { name: "完成一组", exact: true }).click();
  await expect(page.getByLabel("次数", { exact: true })).toHaveValue("8");
  expect(saved).toHaveLength(1);
  expect(saved[0]).toMatchObject({ weightKg: 60, reps: 10, isWarmup: false, isFailure: false });
  expect(saved[0]).not.toHaveProperty("rpe");
  await expect(timer).toHaveText("01:30");
  await page.clock.setFixedTime(new Date(start.getTime() + 1_000));
  await expect(timer).toHaveText("01:29");

  await page.clock.setFixedTime(new Date(start.getTime() + 95_000));
  await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
  await expect(timer).toHaveText("00:00");
  await expect(page.getByText("休息结束")).toBeVisible();
  await page.getByRole("button", { name: "重置休息" }).click();
  await expect(timer).toHaveText("01:30");
  await page.getByRole("button", { name: "跳过休息" }).click();
  await expect(timer).toHaveCount(0);
  expect(saved).toHaveLength(1);

  await page.getByLabel("重量 kg", { exact: true }).fill("62.5");
  await page.getByRole("button", { name: "完成一组", exact: true }).click();
  await expect.poll(() => saved.length).toBe(2);
  expect(saved[1]).toMatchObject({ weightKg: 62.5, reps: 8 });
  await expect(timer).toHaveText("01:30");
});

test("计划进度独立计算每个动作的正式组数，并能定位待练动作", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript(() =>
    sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature"),
  );
  const completedAt = "2026-09-12T08:01:00Z";
  const set = { weightKg: 20, reps: 8, isWarmup: false, isFailure: false, completedAt };
  const workout = {
    id: 7, name: "训练进度", status: "IN_PROGRESS", startedAt: completedAt,
    exercises: [
      { id: 71, exerciseId: 1, exerciseName: "卧推", sequence: 1, targetSets: 1, sets: [
        { ...set, id: 701, setNumber: 1 },
        { ...set, id: 702, setNumber: 2 },
      ] },
      { id: 72, exerciseId: 2, exerciseName: "划船", sequence: 2, targetSets: 2, sets: [
        { ...set, id: 703, setNumber: 1, isWarmup: true },
        { ...set, id: 704, setNumber: 2, completedAt: null },
      ] },
      { id: 73, exerciseId: 3, exerciseName: "加练深蹲", sequence: 3, sets: [
        { ...set, id: 705, setNumber: 1 },
      ] },
    ],
  };
  await page.route("**/api/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    const ok = (data: unknown) => route.fulfill({ json: { code: 0, data } });
    if (path === "/api/v1/workouts/active/current") return ok(workout);
    if (path.endsWith("/previous-sets")) return ok([]);
    if (path === "/api/v1/workouts") return ok({ items: [], total: 0 });
    return route.fulfill({ status: 404, json: { code: 30001, message: "training plan not found" } });
  });

  await page.goto("/workouts");
  const bar = page.getByRole("progressbar", { name: "计划组数进度" });
  await expect(bar).toHaveAttribute("max", "3");
  await expect(bar).toHaveAttribute("value", "1");
  await expect(page.getByText("正式组 2 / 1 组", { exact: true })).toBeVisible();
  await expect(page.getByText("正式组 0 / 2 组", { exact: true })).toBeVisible();
  await expect(page.getByText("正式组 1 组", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "定位待练动作：划船" }).click();
  await expect(page.getByRole("heading", { name: "划船" })).toBeFocused();
});

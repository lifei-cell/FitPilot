import { expect, test } from "@playwright/test";

test("查看并修改未激活计划", async ({ page }) => {
  let planName = "Playwright 力量计划";
  let submittedVersion = 0;
  const plan = () => ({
    id: 42,
    name: planName,
    description: "端到端计划",
    goal: "STRENGTH",
    durationWeeks: 8,
    daysPerWeek: 1,
    status: "DRAFT",
    version: submittedVersion + 1,
    days: [
      {
        id: 11,
        dayNumber: 1,
        name: "上肢力量",
        exercises: [
          {
            id: 21,
            exerciseId: 1,
            exerciseName: "杠铃卧推",
            sequence: 1,
            targetSets: 4,
            targetRepsMin: 5,
            targetRepsMax: 8,
            targetRpe: 8,
            restSeconds: 120,
          },
        ],
      },
    ],
  });

  await page.addInitScript(() => {
    sessionStorage.setItem("fitpilot_access", "header.eyJzdWIiOiI5OSJ9.signature");
  });
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === "/api/v1/training-plans" && request.method() === "GET") {
      return route.fulfill({
        json: {
          code: 0,
          message: "success",
          data: {
            items: [
              {
                id: 42,
                name: planName,
                goal: "STRENGTH",
                durationWeeks: 8,
                daysPerWeek: 1,
                status: "DRAFT",
              },
            ],
            total: 1,
            page: 1,
            size: 50,
            pages: 1,
          },
        },
      });
    }
    if (url.pathname === "/api/v1/training-plans/42" && request.method() === "GET") {
      return route.fulfill({ json: { code: 0, message: "success", data: plan() } });
    }
    if (url.pathname === "/api/v1/training-plans/42" && request.method() === "PUT") {
      const body = request.postDataJSON() as { name: string; version: number };
      planName = body.name;
      submittedVersion = body.version;
      return route.fulfill({ json: { code: 0, message: "success", data: plan() } });
    }
    if (url.pathname === "/api/v1/exercises") {
      return route.fulfill({
        json: {
          code: 0,
          message: "success",
          data: { items: [], total: 0, page: 1, size: 20, pages: 0 },
        },
      });
    }
    return route.fulfill({ status: 404, json: { code: 404, message: "unmocked", data: null } });
  });

  await page.goto("/plans");
  await page.getByRole("button", { name: "查看" }).click();
  await expect(page.getByText("杠铃卧推")).toBeVisible();
  await page.getByRole("button", { name: "修改计划" }).click();
  await page.getByRole("textbox", { name: "计划名称" }).fill("Playwright 调整版");
  await page.getByRole("button", { name: "保存修改" }).click();

  await expect(page.getByRole("heading", { name: "Playwright 调整版", level: 3 })).toBeVisible();
  await expect(page.getByText("计划修改已保存")).toBeVisible();
  expect(submittedVersion).toBe(1);
});
